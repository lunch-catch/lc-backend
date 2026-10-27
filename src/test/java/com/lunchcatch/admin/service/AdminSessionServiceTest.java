package com.lunchcatch.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.lunchcatch.admin.dto.AdminLoginResponse;
import com.lunchcatch.admin.dto.AdminLoginResult;
import com.lunchcatch.admin.repository.AdminSessionRefreshTokenRepository;
import com.lunchcatch.admin.service.AdminSessionTransactionService.*;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.auth.opaque.RefreshTokenRepository.RefreshTokenData;
import com.lunchcatch.auth.opaque.RefreshTokenRepository.RotateOutcome;
import com.lunchcatch.auth.opaque.TokenHasher;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

class AdminSessionServiceTest {
    private final AdminSessionTransactionService transactions = mock(AdminSessionTransactionService.class);
    private final AdminSessionRefreshTokenRepository tokens = mock(AdminSessionRefreshTokenRepository.class);
    private final AdminLogoutTransactionService logoutAudit = mock(AdminLogoutTransactionService.class);
    private final AdminTokenTransactionService tokenAudit = mock(AdminTokenTransactionService.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final AdminSessionService service = new AdminSessionService(transactions, tokens, logoutAudit, tokenAudit, jwt, clock);
    private final String oldHash = TokenHasher.sha256("old");
    private final RefreshTokenData owner = new RefreshTokenData(10L, Role.ADMIN, true);
    private final AdminLoginResult result = new AdminLoginResult(new AdminLoginResponse(1L, "관리자", Role.ADMIN), "access", "refresh");
    private final SessionState state = new SessionState(result, 10L, 2L, LocalDateTime.now(clock).plusDays(1));
    private final Revocation revoked = new Revocation(1L, 10L, Role.ADMIN, oldHash, 3L);

    @BeforeEach
    void setUp() {
        when(jwt.refreshTokenValidityMs(Role.ADMIN)).thenReturn(Duration.ofDays(1).toMillis());
    }

    @Test
    void DB_발급_후_세션별_캐시에_게시한다() {
        when(transactions.issue(1L, "password")).thenReturn(state);
        assertThat(service.issue(1L, "password")).isSameAs(result);
        verify(tokens).saveIfNewer("refresh", 10L, Role.ADMIN, Duration.ofDays(1), 2L);
    }

    @Test
    void 서머타임_전환을_지나는_캐시_TTL은_실제_남은_시간으로_계산한다() {
        Clock transitionClock = Clock.fixed(Instant.parse("2026-03-08T06:30:00Z"), ZoneId.of("America/New_York"));
        var transitionService = new AdminSessionService(transactions, tokens, logoutAudit, tokenAudit, jwt, transitionClock);
        var transitionState = new SessionState(result, 10L, 2L, LocalDateTime.of(2026, 3, 8, 3, 30));
        when(transactions.issue(1L, "password")).thenReturn(transitionState);

        assertThat(transitionService.issue(1L, "password")).isSameAs(result);

        verify(tokens).saveIfNewer("refresh", 10L, Role.ADMIN, Duration.ofHours(1), 2L);
    }

    @Test
    void DB_저장_장애는_AUTH002다() {
        when(transactions.issue(1L, "password")).thenThrow(dbFailure());
        assertCode(() -> service.issue(1L, "password"), AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    void 캐시_게시만_실패하면_DB_백업으로_성공을_유지한다() {
        when(transactions.issue(1L, "password")).thenReturn(state);
        when(tokens.saveIfNewer(any(), any(), any(), any(), anyLong())).thenThrow(dbFailure());
        assertThat(service.issue(1L, "password")).isSameAs(result);
    }

    @Test
    void 만료된_발급_결과는_캐시에_게시하지_않는다() {
        when(transactions.issue(1L, "password")).thenReturn(new SessionState(result, 10L, 1L, LocalDateTime.now(clock)));
        assertThat(service.issue(1L, "password")).isSameAs(result);
        verifyNoInteractions(tokens);
    }

    @Test
    void Redis_정상_회전_뒤_DB를_확정한다() {
        prepareRotation();
        assertThat(service.reissue("old")).contains(result);
        verify(tokens).compareAndRotate(eq("old"), any(), any());
    }

    @Test
    void 캐시가_없으면_DB_현재_해시로_복구한다() {
        prepareFallback();
        assertThat(service.reissue("old")).contains(result);
        verify(tokens, never()).compareAndRotate(any(), any(), any());
    }

    @Test
    void 캐시_조회_장애도_DB_현재_해시로_복구한다() {
        prepareFallback();
        when(tokens.find("old")).thenThrow(dbFailure());
        assertThat(service.reissue("old")).contains(result);
    }

    @Test
    void 캐시가_정상적으로_NOT_FOUND를_판정하면_DB로_우회하지_않는다() {
        prepareRotation();
        when(tokens.compareAndRotate(eq("old"), any(), any())).thenReturn(RotateOutcome.notFound());
        assertCode(() -> service.reissue("old"), AuthErrorCode.REFRESH_TOKEN_INVALID);
        verify(transactions, never()).rotate(any(), any(), any(), any());
    }

    @Test
    void 비관리자_캐시_토큰은_거부한다() {
        when(tokens.find("old")).thenReturn(Optional.of(new RefreshTokenData(10L, Role.OWNER, true)));
        assertCode(() -> service.reissue("old"), AuthErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void 모르는_토큰은_기존_호환_경로에_넘긴다() {
        assertThat(service.reissue("unknown")).isEmpty();
    }

    @Test
    void 회전_접속_실패는_DB로_복구한다() {
        prepareRotation();
        when(tokens.compareAndRotate(eq("old"), any(), any())).thenThrow(dbFailure());
        assertThat(service.reissue("old")).contains(result);
    }

    @Test
    void 회전_타임아웃은_새_Redis_레코드를_확인한_뒤_진행한다() {
        prepareRotation();
        when(tokens.compareAndRotate(eq("old"), any(), any())).thenThrow(new QueryTimeoutException("timeout"));
        when(tokens.find(argThat(token -> !"old".equals(token)))).thenReturn(Optional.of(owner));
        assertThat(service.reissue("old")).contains(result);
    }

    @Test
    void 회전_타임아웃_결과가_불명이면_503이다() {
        prepareRotation();
        when(tokens.compareAndRotate(eq("old"), any(), any())).thenThrow(new QueryTimeoutException("timeout"));
        assertCode(() -> service.reissue("old"), AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(transactions, never()).rotate(any(), any(), any(), any());
    }

    @Test
    void 회전_타임아웃_후_조회도_실패하면_503이다() {
        prepareRotation();
        when(tokens.compareAndRotate(eq("old"), any(), any())).thenThrow(new QueryTimeoutException("timeout"));
        when(tokens.find(argThat(token -> !"old".equals(token)))).thenThrow(dbFailure());
        assertCode(() -> service.reissue("old"), AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    void DB_커밋이_확인되면_회전_성공을_복원한다() {
        prepareRotation();
        when(transactions.rotate(eq(10L), eq(Role.ADMIN), eq(oldHash), any())).thenThrow(dbFailure());
        when(transactions.confirmRotation(eq(10L), eq(Role.ADMIN), eq(oldHash), any())).thenReturn(new Recovery(state, false));
        assertThat(service.reissue("old")).contains(result);
    }

    @Test
    void DB_롤백이_확인되면_이전_Redis_토큰을_복원하고_오류를_유지한다() {
        prepareRotation();
        when(transactions.rotate(eq(10L), eq(Role.ADMIN), eq(oldHash), any())).thenThrow(dbFailure());
        when(transactions.confirmRotation(eq(10L), eq(Role.ADMIN), eq(oldHash), any())).thenReturn(new Recovery(null, true));
        when(tokens.rollbackRotation(eq(oldHash), any(), eq(Role.ADMIN), eq(10L))).thenReturn(true);
        assertCode(() -> service.reissue("old"), AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(tokens, never()).revokeIfActiveHashMatches(any(), any(), any());
    }

    @Test
    void 복구가_불명이면_이번_회전_캐시만_정리한다() {
        prepareRotation();
        when(transactions.rotate(eq(10L), eq(Role.ADMIN), eq(oldHash), any())).thenThrow(dbFailure());
        when(transactions.confirmRotation(eq(10L), eq(Role.ADMIN), eq(oldHash), any())).thenThrow(dbFailure());
        assertCode(() -> service.reissue("old"), AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(tokens).revokeIfActiveHashMatches(any(), eq(Role.ADMIN), eq(10L));
    }

    @Test
    void 재사용은_해당_세션_폐기를_먼저_커밋하고_실패를_기록한다() {
        prepareRotation();
        when(tokens.compareAndRotate(eq("old"), any(), any())).thenReturn(RotateOutcome.reuseDetected(owner));
        when(transactions.revokeReused(10L, Role.ADMIN)).thenReturn(revoked);
        assertCode(() -> service.reissue("old"), AuthErrorCode.REFRESH_TOKEN_REUSED);
        verify(tokens).revokeBeforeVersion(10L, Role.ADMIN, 3L);
        verify(tokenAudit).recordFailure(1L, "REUSE_DETECTED");
    }

    @Test
    void 재사용_감사_장애는_폐기_결과를_바꾸지_않는다() {
        prepareRotation();
        when(tokens.compareAndRotate(eq("old"), any(), any())).thenReturn(RotateOutcome.reuseDetected(owner));
        when(transactions.revokeReused(10L, Role.ADMIN)).thenReturn(revoked);
        doThrow(dbFailure()).when(tokenAudit).recordFailure(1L, "REUSE_DETECTED");
        assertCode(() -> service.reissue("old"), AuthErrorCode.REFRESH_TOKEN_REUSED);
    }

    @Test
    void 로그아웃은_DB_폐기_후_그_세션의_Redis만_정리한다() {
        when(transactions.logout(1L, Role.ADMIN, 1L)).thenReturn(Optional.of(revoked));
        assertThat(service.logout(1L, Role.ADMIN, 1L)).isTrue();
        verify(tokens).revokeBeforeVersion(10L, Role.ADMIN, 3L);
        verify(logoutAudit).recordSuccess(1L);
        assertThat(service.logout(1L, Role.ADMIN, 9L)).isFalse();
    }

    @Test
    void 로그아웃_Redis_실패는_503이며_성공_감사를_생략한다() {
        when(transactions.logout(1L, Role.ADMIN, 1L)).thenReturn(Optional.of(revoked));
        doThrow(dbFailure()).when(tokens).revokeBeforeVersion(10L, Role.ADMIN, 3L);
        assertCode(() -> service.logout(1L, Role.ADMIN, 1L), AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verifyNoInteractions(logoutAudit);
    }

    @Test
    void 로그아웃_감사_실패는_폐기를_취소하지_않는다() {
        when(transactions.logout(1L, Role.ADMIN, 1L)).thenReturn(Optional.of(revoked));
        doThrow(dbFailure()).when(logoutAudit).recordSuccess(1L);
        assertThat(service.logout(1L, Role.ADMIN, 1L)).isTrue();
    }

    @Test
    void AT_검증은_DB_세션_상태를_따른다() {
        when(transactions.isValid(1L, Role.ADMIN, 1L)).thenReturn(Optional.of(false));
        assertThat(service.isValid(1L, Role.ADMIN, 1L)).contains(false);
    }

    private void prepareRotation() {
        when(tokens.find("old")).thenReturn(Optional.of(owner));
        when(tokens.compareAndRotate(eq("old"), any(), any())).thenReturn(RotateOutcome.success(owner));
        when(transactions.rotate(eq(10L), eq(Role.ADMIN), eq(oldHash), any())).thenReturn(state);
    }

    private void prepareFallback() {
        when(transactions.findByHash(oldHash)).thenReturn(Optional.of(new SessionOwner(10L, Role.ADMIN)));
        when(transactions.rotate(eq(10L), eq(Role.ADMIN), eq(oldHash), any())).thenReturn(state);
    }

    private DataAccessResourceFailureException dbFailure() { return new DataAccessResourceFailureException("unavailable"); }

    private void assertCode(Runnable action, AuthErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(code);
    }
}
