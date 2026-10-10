package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerLogoutTokenRepository;
import com.launchcatch.owner.repository.OwnerRepository;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OwnerLogoutServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final OwnerLogoutTokenRepository tokens = mock(OwnerLogoutTokenRepository.class);
    private final OwnerAccessTokenVersionRepository cutoff = mock(OwnerAccessTokenVersionRepository.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ClockConfig.ZONE);
    private Owner owner;
    private OwnerLogoutService service;

    @BeforeEach
    void setUp() {
        owner = Owner.create("owner@example.com", "hash");
        owner.recordLogin("a".repeat(64), LocalDateTime.now(clock).plusDays(14), LocalDateTime.now(clock));
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jwt.getAccessTokenValidityMs()).thenReturn(1800000L);
        service = new OwnerLogoutService(owners, tokens, cutoff, manager);
    }

    @ParameterizedTest
    @EnumSource(OwnerStatus.class)
    void 상태와_무관하게_RT를_지우고_AT를_차단한다(OwnerStatus status) {
        ReflectionTestUtils.setField(owner, "status", status);
        service.logout(7L);
        assertThat(owner.getRefreshTokenHash()).isNull();
        assertThat(owner.getRefreshTokenExpiresAt()).isNull();
        assertThat(owner.getRefreshTokenIssuanceVersion()).isEqualTo(2);
        verify(tokens).revokeThroughVersion(7L, "a".repeat(64), 2L);
        verify(cutoff).invalidateThroughVersion(7L, 2L);
    }

    @Test
    void RT가_이미_없어도_폐기를_다시_시도한다() {
        owner.clearRefreshToken();
        service.logout(7L);
        verify(tokens).revokeThroughVersion(7L, null, 3L);
    }

    @Test
    void 없는_점주는_인증_실패다() {
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.logout(7L)).isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException)e).getErrorCode().getCode()).isEqualTo("AUTH-005");
        verifyNoInteractions(tokens, cutoff);
    }

    @Test
    void DB_실패시_Redis를_변경하지_않는다() {
        doThrow(new DataAccessResourceFailureException("DB")).when(owners).saveAndFlush(any());
        assertUnavailable();
        verifyNoInteractions(tokens, cutoff);
    }

    @Test
    void 커밋_실패시_Redis를_변경하지_않는다() {
        doThrow(new UnexpectedRollbackException("commit")).when(manager).commit(any());
        assertUnavailable();
        verifyNoInteractions(tokens, cutoff);
    }

    @Test
    void RT_폐기_실패에도_AT_차단을_시도한다() {
        doThrow(new DataAccessResourceFailureException("Redis RT")).when(tokens).revokeThroughVersion(any(), any(), anyLong());
        assertUnavailable();
        verify(cutoff).invalidateThroughVersion(7L, 2L);
    }

    @Test
    void AT_차단_실패도_503이다() {
        doThrow(new DataAccessResourceFailureException("Redis AT")).when(cutoff).invalidateThroughVersion(any(), anyLong());
        assertUnavailable();
        assertThat(owner.getRefreshTokenHash()).isNull();
    }

    @Test
    void 두_Redis_작업이_모두_실패해도_503이다() {
        doThrow(new DataAccessResourceFailureException("Redis RT")).when(tokens).revokeThroughVersion(any(), any(), anyLong());
        doThrow(new DataAccessResourceFailureException("Redis AT")).when(cutoff).invalidateThroughVersion(any(), anyLong());
        assertUnavailable();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> service.logout(7L)).isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException)e).getErrorCode().getCode()).isEqualTo("AUTH-002");
    }
}
