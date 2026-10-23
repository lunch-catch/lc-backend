package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.owner.repository.OwnerRefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OwnerTokenRefreshServiceTest {
    private static final String OLD_TOKEN = "old-refresh";
    private static final String OLD_HASH = TokenHasher.sha256(OLD_TOKEN);
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final OwnerRefreshTokenRepository ownerTokens = mock(OwnerRefreshTokenRepository.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final OwnerAccessTokenVersionRepository cutoff = mock(OwnerAccessTokenVersionRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T02:00:00Z"), ClockConfig.ZONE);
    private final LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ClockConfig.ZONE);
    private Owner owner;
    private OwnerTokenRefreshService service;

    @BeforeEach
    void setUp() {
        owner = ownerWithToken();
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        when(owners.findByRefreshTokenHash(OLD_HASH)).thenReturn(Optional.of(owner));
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenReturn(success());
        when(jwt.refreshTokenValidityMs(Role.OWNER)).thenReturn(Duration.ofDays(1).toMillis());
        when(jwt.getAccessTokenValidityMs()).thenReturn(Duration.ofMinutes(30).toMillis());
        when(jwt.createAccessToken(eq(7L), eq(Role.OWNER), anyMap())).thenReturn("new-access");
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(tokens.saveIfNewer(anyString(), eq(7L), eq(Role.OWNER), eq(true), any(), anyLong())).thenReturn(true);
        service = new OwnerTokenRefreshService(owners, tokens, ownerTokens, jwt, cutoff, clock, manager);
    }

    private Owner ownerWithToken() {
        Owner value = Owner.create("owner@example.com", "password-hash");
        ReflectionTestUtils.setField(value, "id", 7L);
        value.recordLogin(OLD_HASH, now.plusDays(1), now.minusDays(1));
        return value;
    }

    private OwnerRefreshTokenRepository.ConsumeOutcome success() {
        return OwnerRefreshTokenRepository.ConsumeOutcome.success(new RefreshTokenRepository.RefreshTokenData(7L, Role.OWNER, true));
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ONBOARDING", "ACTIVE"})
    void 현재_DB_상태로_회전하며_최근_로그인_시각은_유지한다(OwnerStatus status) {
        ReflectionTestUtils.setField(owner, "status", status);
        ReflectionTestUtils.setField(owner, "tutorialViewed", true);

        var result = service.refresh(OLD_TOKEN);

        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo(OLD_TOKEN);
        assertThat(owner.getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(result.refreshToken()));
        assertThat(owner.getRefreshTokenExpiresAt()).isEqualTo(now.plusDays(1));
        assertThat(owner.getLastLoginAt()).isEqualTo(now.minusDays(1));
        assertThat(owner.getRefreshTokenIssuanceVersion()).isEqualTo(2L);
        assertThat(owner.getAccessTokenRevokedVersion()).isEqualTo(-1L);
        assertThat(result.response().status()).isEqualTo(status);
        assertThat(result.response().tutorialViewed()).isEqualTo(status == OwnerStatus.ACTIVE ? true : null);
        assertThat(result.toString()).doesNotContain(result.accessToken(), result.refreshToken(), "owner@example.com");
        verify(jwt).createAccessToken(7L, Role.OWNER, java.util.Map.of("issuanceVersion", "2"));
        verify(tokens).saveIfNewer(result.refreshToken(), 7L, Role.OWNER, true, Duration.ofDays(1), 2L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void 쿠키가_없거나_비어있으면_거절한다(String token) {
        assertCode(() -> service.refresh(token), "AUTH-003");
        verifyNoInteractions(tokens, ownerTokens, owners);
    }

    @Test
    void Redis_정상_미스에는_DB_폴백하지_않는다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenReturn(OwnerRefreshTokenRepository.ConsumeOutcome.notFound());
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
        verifyNoInteractions(owners);
    }

    @Test
    void 다른_역할의_RT는_폐기하거나_회전하지_않는다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenReturn(OwnerRefreshTokenRepository.ConsumeOutcome.reuseDetected(
                new RefreshTokenRepository.RefreshTokenData(7L, Role.ADMIN, true)));
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
        verifyNoInteractions(owners, cutoff);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void Redis가_성공해도_DB의_정지_탈퇴는_거절한다(OwnerStatus status) {
        ReflectionTestUtils.setField(owner, "status", status);
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
        verify(tokens, never()).saveIfNewer(anyString(), anyLong(), any(), anyBoolean(), any(), anyLong());
    }

    @Test
    void Redis에_이전_RT가_남아도_DB_현재_해시와_다르면_거절한다() {
        ReflectionTestUtils.setField(owner, "refreshTokenHash", TokenHasher.sha256("newer-login"));
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
        verify(owners, never()).saveAndFlush(any());
    }

    @Test
    void DB_RT_만료_경계는_거절한다() {
        ReflectionTestUtils.setField(owner, "refreshTokenExpiresAt", now);
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
    }

    @Test
    void DB에_만료시각이_없으면_거절한다() {
        ReflectionTestUtils.setField(owner, "refreshTokenExpiresAt", null);
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
    }

    @Test
    void DB에_점주가_없으면_거절한다() {
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.empty());
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
    }

    @Test
    void Redis_장애는_DB_유효_해시로_폴백한다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenThrow(failure()).thenReturn(success());
        var result = service.refresh(OLD_TOKEN);
        assertThat(owner.getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(result.refreshToken()));
        verify(jwt).createAccessToken(7L, Role.OWNER, java.util.Map.of("issuanceVersion", "2"));
        verify(tokens).saveIfNewer(result.refreshToken(), 7L, Role.OWNER, true, Duration.ofDays(1), 2L);
    }

    @Test
    void Redis가_계속_장애여도_DB_회전이_성공하면_유지한다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenThrow(failure());
        assertThat(service.refresh(OLD_TOKEN).accessToken()).isEqualTo("new-access");
    }

    @Test
    void 폴백에서_DB_해시가_없으면_거절한다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenThrow(failure());
        when(owners.findByRefreshTokenHash(OLD_HASH)).thenReturn(Optional.empty());
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
    }

    @Test
    void 폴백_DB_조회_장애는_503이다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenThrow(failure());
        when(owners.findByRefreshTokenHash(OLD_HASH)).thenThrow(failure());
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-002");
    }

    @Test
    void 폴백_조회_이후_다른_요청이_회전하면_두번째_회전은_거절한다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenThrow(failure());
        Owner changed = ownerWithToken();
        changed.rotateRefreshToken(TokenHasher.sha256("winner"), now.plusDays(1), now);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(changed));
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
        verify(owners, never()).saveAndFlush(any());
    }

    @Test
    void DB_실패시_기존_유효_RT가_유지되면_소비표시를_복구한다() {
        Owner unchanged = ownerWithToken();
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner), Optional.of(unchanged));
        when(owners.saveAndFlush(any())).thenThrow(failure());
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-002");
        verify(ownerTokens).restoreConsumed(OLD_HASH);
        verify(tokens, never()).saveIfNewer(anyString(), anyLong(), any(), anyBoolean(), any(), anyLong());
    }

    @Test
    void DB_실패_후_최신_RT가_변경되었으면_기존_RT를_복구하지_않는다() {
        when(owners.saveAndFlush(any())).thenThrow(failure());
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-002");
        verify(ownerTokens, never()).restoreConsumed(anyString());
    }

    @Test
    void 커밋_실패도_503으로_처리한다() {
        doThrow(new UnexpectedRollbackException("commit failed")).when(manager).commit(any());
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-002");
    }

    @Test
    void 복구_확인이_실패하면_토큰을_되살리지_않는다() {
        when(owners.findByIdForLogin(7L))
                .thenThrow(new DataAccessResourceFailureException("sensitive-restore-error " + OLD_TOKEN + " " + OLD_HASH));
        try (var logs = LogCapture.attach(OwnerTokenRefreshService.class)) {
            assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-002");
            var event = logs.only();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=OWNER_REFRESH_RESTORE_FAILED", "ownerId=7",
                    "exceptionType=DataAccessResourceFailureException")
                    .doesNotContain("sensitive-restore-error", OLD_TOKEN, OLD_HASH, "stack=");
            assertThat(event.getThrowableProxy()).isNull();
        }
        verify(ownerTokens, never()).restoreConsumed(anyString());
    }

    @Test
    void Redis_게시만_실패하면_DB_성공을_유지한다() {
        when(tokens.saveIfNewer(anyString(), anyLong(), any(), anyBoolean(), any(), anyLong()))
                .thenAnswer(call -> {
                    throw new DataAccessResourceFailureException("sensitive-publish-error " + call.getArgument(0)
                            + " " + owner.getRefreshTokenHash() + " " + owner.getEmail());
                });
        try (var logs = LogCapture.attach(OwnerTokenRefreshService.class)) {
            var result = service.refresh(OLD_TOKEN);
            assertThat(result.accessToken()).isEqualTo("new-access");
            var event = logs.only();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=OWNER_REFRESH_CACHE_PUBLISH_FAILED", "ownerId=7",
                    "exceptionType=DataAccessResourceFailureException", "fallback=KEEP_DB_REFRESH")
                    .doesNotContain("sensitive-publish-error", OLD_TOKEN, OLD_HASH, owner.getEmail(),
                            result.refreshToken(), owner.getRefreshTokenHash(), "stack=");
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    @Test
    void 게시_직전_새_로그인이_있으면_이전_재발급을_게시하지_않는다() {
        Owner newer = ownerWithToken();
        newer.recordLogin(TokenHasher.sha256("new-login"), now.plusDays(1), now);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner), Optional.of(newer));
        assertThat(service.refresh(OLD_TOKEN).accessToken()).isEqualTo("new-access");
        verify(tokens, never()).saveIfNewer(anyString(), anyLong(), any(), anyBoolean(), any(), anyLong());
    }

    @Test
    void 게시_직전_DB_확인_장애는_503이다() {
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner)).thenThrow(failure());
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-002");
    }

    @Test
    void 게시_직전_정지된_점주에게_발급하지_않는다() {
        Owner suspended = ownerWithToken();
        ReflectionTestUtils.setField(suspended, "status", OwnerStatus.SUSPENDED);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner), Optional.of(suspended));
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
    }

    @Test
    void 게시_직전_RT가_만료되면_거절한다() {
        Clock movingClock = mock(Clock.class);
        when(movingClock.instant()).thenReturn(clock.instant(), clock.instant().plus(Duration.ofDays(1)));
        service = new OwnerTokenRefreshService(owners, tokens, ownerTokens, jwt, cutoff, movingClock, manager);
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
    }

    @Test
    void RT_재사용은_계정_RT를_폐기하고_AT_컷오프를_설정한다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenReturn(OwnerRefreshTokenRepository.ConsumeOutcome.reuseDetected(
                new RefreshTokenRepository.RefreshTokenData(7L, Role.OWNER, true)));
        try (var logs = LogCapture.attach(OwnerTokenRefreshService.class)) {
            assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-004");
            var event = logs.only();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=OWNER_REFRESH_REUSE_SUSPECTED", "ownerId=7")
                    .doesNotContain("OWNER_REFRESH_REUSE_DETECTED", OLD_TOKEN, OLD_HASH, owner.getEmail());
            assertThat(event.getThrowableProxy()).isNull();
        }
        assertThat(owner.getRefreshTokenHash()).isNull();
        assertThat(owner.getRefreshTokenExpiresAt()).isNull();
        assertThat(owner.getRefreshTokenIssuanceVersion()).isEqualTo(2L);
        assertThat(owner.getAccessTokenRevokedVersion()).isEqualTo(2L);
        verify(ownerTokens).revokeThroughVersion(7L, OLD_HASH, 2L);
        verify(cutoff).invalidateThroughVersion(7L, 2L);
    }

    @Test
    void 재사용_폐기_장애는_503이다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenReturn(OwnerRefreshTokenRepository.ConsumeOutcome.reuseDetected(
                new RefreshTokenRepository.RefreshTokenData(7L, Role.OWNER, true)));
        doThrow(failure()).when(ownerTokens).revokeThroughVersion(anyLong(), anyString(), anyLong());
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-002");
        assertThat(owner.getRefreshTokenHash()).isNull();
    }

    @Test
    void 재사용_토큰의_점주가_이미_삭제되어도_재사용으로_거절한다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenReturn(OwnerRefreshTokenRepository.ConsumeOutcome.reuseDetected(
                new RefreshTokenRepository.RefreshTokenData(7L, Role.OWNER, true)));
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.empty());
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-004");
    }

    @Test
    void Redis_장애_로그에_예외메시지의_RT를_노출하지_않는다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN))
                .thenThrow(new DataAccessResourceFailureException("sensitive-refresh-token " + OLD_TOKEN
                        + " " + OLD_HASH + " " + owner.getEmail())).thenReturn(success());
        try (var logs = LogCapture.attach(OwnerTokenRefreshService.class)) {
            var result = service.refresh(OLD_TOKEN);
            assertThat(result.accessToken()).isEqualTo("new-access");
            var event = logs.only();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=OWNER_REFRESH_DB_FALLBACK",
                    "exceptionType=DataAccessResourceFailureException", "fallback=DB")
                    .doesNotContain("sensitive-refresh-token", OLD_TOKEN, OLD_HASH, owner.getEmail(),
                            result.accessToken(), result.refreshToken(), owner.getRefreshTokenHash(), "stack=", "errorType=");
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    @Test
    void 진행중인_중복요청은_계정_토큰을_폐기하지_않는다() {
        when(ownerTokens.consumeForRotation(OLD_TOKEN)).thenReturn(OwnerRefreshTokenRepository.ConsumeOutcome.inProgress(
                new RefreshTokenRepository.RefreshTokenData(7L, Role.OWNER, true)));
        assertCode(() -> service.refresh(OLD_TOKEN), "AUTH-003");
        assertThat(owner.getRefreshTokenHash()).isEqualTo(OLD_HASH);
        verifyNoInteractions(cutoff);
    }

    @Test
    void 확정표시_장애만으로_DB_성공을_취소하지_않는다() {
        doThrow(new DataAccessResourceFailureException("sensitive-confirm-error " + OLD_TOKEN + " " + OLD_HASH))
                .when(ownerTokens).confirmConsumed(OLD_HASH);
        try (var logs = LogCapture.attach(OwnerTokenRefreshService.class)) {
            var result = service.refresh(OLD_TOKEN);
            assertThat(result.accessToken()).isEqualTo("new-access");
            var event = logs.only();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=OWNER_REFRESH_CONFIRM_FAILED", "ownerId=7",
                    "exceptionType=DataAccessResourceFailureException", "fallback=DB_HASH_CHECK")
                    .doesNotContain("sensitive-confirm-error", OLD_TOKEN, OLD_HASH, result.refreshToken(), "stack=");
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    private DataAccessResourceFailureException failure() {
        return new DataAccessResourceFailureException("unavailable");
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
    }
}
