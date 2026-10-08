package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.owner.dto.OwnerLoginRequest;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OwnerLoginServiceTest {
    private final OwnerRepository repository = mock(OwnerRepository.class);
    private final BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final RefreshTokenRepository cache = mock(RefreshTokenRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T02:00:00Z"), ClockConfig.ZONE);
    private final OwnerLoginRequest request = new OwnerLoginRequest("owner@example.com", "password12");
    private Owner owner;
    private OwnerLoginService service;

    @BeforeEach
    void setUp() {
        owner = Owner.create(request.email(), encoder.encode(request.password()));
        ReflectionTestUtils.setField(owner, "id", 7L);
        when(repository.findByEmail(request.email())).thenReturn(Optional.of(owner));
        when(repository.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(jwt.refreshTokenValidityMs(Role.OWNER)).thenReturn(Duration.ofDays(14).toMillis());
        when(jwt.createAccessToken(7L, Role.OWNER)).thenReturn("test-access");
        service = new OwnerLoginService(repository, encoder, jwt, cache, clock, manager);
        clearInvocations(encoder);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ONBOARDING", "ACTIVE"})
    void 로그인_성공은_해시와_시각을_저장하고_상태를_응답에_반영한다(OwnerStatus status) {
        ReflectionTestUtils.setField(owner, "status", status);
        var result = service.login(request);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ClockConfig.ZONE);
        assertThat(owner.getLastLoginAt()).isEqualTo(now);
        assertThat(owner.getRefreshTokenExpiresAt()).isEqualTo(now.plusDays(14));
        assertThat(owner.getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(result.refreshToken()));
        assertThat(result.response().email()).isEqualTo(request.email());
        assertThat(result.response().role()).isEqualTo(Role.OWNER);
        assertThat(result.response().status()).isEqualTo(status);
        if (status == OwnerStatus.ACTIVE) {
            assertThat(result.response().tutorialViewed()).isFalse();
        } else {
            assertThat(result.response().tutorialViewed()).isNull();
        }
        assertThat(result.toString()).doesNotContain(result.accessToken(), result.refreshToken(), request.email());
        verify(jwt).createAccessToken(7L, Role.OWNER);
        var order = inOrder(manager, cache);
        order.verify(manager).commit(any());
        order.verify(cache).save(result.refreshToken(), 7L, Role.OWNER, true, Duration.ofDays(14));
    }

    @Test
    void 확인한_튜토리얼은_ACTIVE_응답에_true로_반환한다() {
        ReflectionTestUtils.setField(owner, "status", OwnerStatus.ACTIVE);
        ReflectionTestUtils.setField(owner, "tutorialViewed", true);
        assertThat(service.login(request).response().tutorialViewed()).isTrue();
    }

    @Test
    void 미존재_계정도_Dummy_BCrypt_비교를_수행한다() {
        when(repository.findByEmail(request.email())).thenReturn(Optional.empty());
        assertLoginFailed(request);
        verify(encoder).matches(eq(request.password()), argThat(hash -> hash.startsWith("$2")));
    }

    @Test
    void 비밀번호_불일치는_동일한_오류로_거절한다() {
        assertLoginFailed(new OwnerLoginRequest(request.email(), "wrongpass12"));
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void 정지와_탈퇴도_동일한_오류로_거절한다(OwnerStatus status) {
        ReflectionTestUtils.setField(owner, "status", status);
        assertLoginFailed(request);
        verify(encoder).matches(request.password(), owner.getPasswordHash());
    }

    @Test
    void 저장_직전_상태가_바뀌면_거절한다() {
        Owner changed = Owner.create(request.email(), owner.getPasswordHash());
        ReflectionTestUtils.setField(changed, "status", OwnerStatus.SUSPENDED);
        when(repository.findByIdForLogin(7L)).thenReturn(Optional.of(changed));
        assertLoginFailed(request);
        verify(manager).rollback(any());
    }

    @Test
    void 저장_직전_비밀번호가_바뀌면_거절한다() {
        String changedPasswordHash = encoder.encode("changedpass12");
        Owner changed = Owner.create(request.email(), changedPasswordHash);

        when(repository.findByIdForLogin(7L))
                .thenReturn(Optional.of(changed));

        assertLoginFailed(request);
    }

    @Test
    void 저장_직전_계정이_사라지면_거절한다() {
        when(repository.findByIdForLogin(7L)).thenReturn(Optional.empty());
        assertLoginFailed(request);
    }

    @Test
    void DB_저장_실패는_503이며_캐시에_저장하지_않는다() {
        when(repository.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("DB unavailable"));
        assertStoreFailed();
        verify(manager).rollback(any());
    }

    @Test
    void 커밋_실패도_503이며_캐시에_저장하지_않는다() {
        doThrow(new UnexpectedRollbackException("commit failed")).when(manager).commit(any());
        assertStoreFailed();
    }

    @Test
    void 캐시_장애는_DB_저장이_성공하면_허용한다() {
        doThrow(new DataAccessResourceFailureException("cache unavailable"))
                .when(cache).save(anyString(), eq(7L), eq(Role.OWNER), eq(true), any(Duration.class));
        assertThat(service.login(request).accessToken()).isEqualTo("test-access");
        verify(manager).commit(any());
    }

    private void assertLoginFailed(OwnerLoginRequest input) {
        assertThatThrownBy(() -> service.login(input)).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-001"));
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(cache);
    }

    private void assertStoreFailed() {
        assertThatThrownBy(() -> service.login(request)).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-002"));
        verifyNoInteractions(cache);
    }
}