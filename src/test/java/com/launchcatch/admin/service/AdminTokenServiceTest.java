package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.launchcatch.admin.dto.AdminLoginResponse;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.RefreshTokenRepository.RefreshTokenData;
import com.launchcatch.auth.opaque.RefreshTokenRepository.RotateOutcome;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionSystemException;

class AdminTokenServiceTest {
    private final RefreshTokenRepository cache = mock(RefreshTokenRepository.class);
    private final AdminTokenTransactionService db = mock(AdminTokenTransactionService.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final AdminTokenService service = new AdminTokenService(cache, db, jwt, clock);
    private final String old = "a".repeat(43);
    private final RefreshTokenData owner = new RefreshTokenData(1L, Role.ADMIN, true);
    private final AdminTokenTransactionService.RotationState state = new AdminTokenTransactionService.RotationState(
            new AdminLoginResponse(1L, "관리자", Role.ADMIN), 2L);

    @BeforeEach
    void setUp() {
        when(db.confirmRotation(any(), any(), any(), any()))
                .thenReturn(new AdminTokenTransactionService.RotationRecovery(null, false));
        when(jwt.refreshTokenValidityMs(Role.ADMIN)).thenReturn(86400000L);
        when(jwt.createAccessToken(1L, Role.ADMIN)).thenReturn("access");
        when(cache.find(old)).thenReturn(Optional.of(owner));
        when(cache.compareAndRotate(eq(old), anyString(), any())).thenReturn(RotateOutcome.success(owner));
        when(db.rotate(eq(1L), eq(TokenHasher.sha256(old)), anyString(), any(), any())).thenReturn(state);
    }

    @Test
    void 정상_회전은_새_토큰과_DB의_현재_관리자정보를_반환한다() {
        var result = service.reissue(old);
        assertThat(result.response()).isEqualTo(state.response());
        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.refreshToken()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(old);
        verify(cache).saveIfNewer(result.refreshToken(), 1L, Role.ADMIN, true, Duration.ofDays(1), 2L);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"short", "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"})
    void 잘못된_쿠키는_저장소_조회없이_거부한다(String token) {
        rejected(token, AuthErrorCode.REFRESH_TOKEN_INVALID);
        verifyNoInteractions(cache, db);
    }

    @Test
    void 다른_역할은_회전하지_않는다() {
        when(cache.find(old)).thenReturn(Optional.of(new RefreshTokenData(1L, Role.MEMBER, true)));
        rejected(old, AuthErrorCode.REFRESH_TOKEN_INVALID);
        verify(cache, never()).compareAndRotate(any(), any(), any());
    }

    @Test
    void 정상_NOT_FOUND는_DB로_우회하지_않는다() {
        when(cache.compareAndRotate(eq(old), any(), any())).thenReturn(RotateOutcome.notFound());
        rejected(old, AuthErrorCode.REFRESH_TOKEN_INVALID);
        verifyNoInteractions(db);
    }

    @Test
    void 조회_장애는_DB_백업으로_회전한다() {
        when(cache.find(old)).thenThrow(new DataAccessResourceFailureException("token-secret"));
        when(db.rotate(isNull(), any(), any(), any(), any())).thenReturn(state);
        assertThat(service.reissue(old).response()).isEqualTo(state.response());
    }

    @Test
    void 캐시_유실은_유효한_DB_백업으로만_복구한다() {
        when(cache.find(old)).thenReturn(Optional.empty());
        when(db.rotate(isNull(), any(), any(), any(), any())).thenReturn(state);
        assertThat(service.reissue(old).response()).isEqualTo(state.response());
    }

    @Test
    void 회전_연결장애는_DB로_폴백한다() {
        when(cache.compareAndRotate(eq(old), any(), any())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThat(service.reissue(old).response()).isEqualTo(state.response());
    }

    @Test
    void 회전_타임아웃은_새_레코드_확인후_DB를_확정한다() {
        when(cache.compareAndRotate(eq(old), any(), any())).thenThrow(new QueryTimeoutException("timeout"));
        when(cache.find(argThat(token -> !old.equals(token)))).thenReturn(Optional.of(owner));
        assertThat(service.reissue(old).response()).isEqualTo(state.response());
    }

    @Test
    void 타임아웃_미확정은_폴백하지_않고_실패감사를_남긴다() {
        when(cache.compareAndRotate(eq(old), any(), any())).thenThrow(new QueryTimeoutException("timeout"));
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(db, never()).rotate(any(), any(), any(), any(), any());
        verify(db).recordFailure(1L, "ROTATION_RESULT_UNKNOWN");
    }

    @Test
    void 타임아웃_확인_조회_장애도_AUTH002다() {
        when(cache.compareAndRotate(eq(old), any(), any())).thenThrow(new QueryTimeoutException("timeout"));
        when(cache.find(argThat(token -> !old.equals(token)))).thenThrow(new DataAccessResourceFailureException("offline"));
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    void DB_실패는_이번_캐시회전을_보상한다() {
        when(db.rotate(any(), any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("db"));
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(cache).revokeIfActiveHashMatches(anyString(), eq(Role.ADMIN), eq(1L));
        verify(db).recordFailure(1L, "DB_ROTATION_FAILED");
    }

    @Test
    void DB_유효성실패도_이번_캐시회전을_보상한다() {
        when(db.rotate(any(), any(), any(), any(), any())).thenThrow(new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        rejected(old, AuthErrorCode.REFRESH_TOKEN_INVALID);
        verify(cache).revokeIfActiveHashMatches(anyString(), eq(Role.ADMIN), eq(1L));
    }

    @Test
    void DB_커밋실패는_AUTH002다() {
        when(db.rotate(any(), any(), any(), any(), any())).thenThrow(new TransactionSystemException("commit"));
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    void 보상_실패와_실패감사_장애에도_AUTH002를_유지한다() {
        when(db.rotate(any(), any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("db"));
        doThrow(new DataAccessResourceFailureException("cache")).when(cache).revokeIfActiveHashMatches(any(), any(), any());
        doThrow(new DataAccessResourceFailureException("audit")).when(db).recordFailure(any(), any());
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    void DB_확정후_캐시_게시실패는_성공한다() {
        when(cache.saveIfNewer(any(), any(), any(), anyBoolean(), any(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("cache"));
        assertThat(service.reissue(old).response()).isEqualTo(state.response());
    }

    @Test
    void 폴백_DB_실패는_AUTH002다() {
        when(cache.find(old)).thenThrow(new DataAccessResourceFailureException("cache"));
        when(db.rotate(isNull(), any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("db"));
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    void 재사용은_DB폐기와_두_관리자역할의_게시차단후_AUTH004다() {
        when(cache.compareAndRotate(eq(old), any(), any())).thenReturn(RotateOutcome.reuseDetected(owner));
        when(db.revokeReused(1L)).thenReturn(new AdminTokenTransactionService.RevocationState(Role.ADMIN, "hash", 3L));
        rejected(old, AuthErrorCode.REFRESH_TOKEN_REUSED);
        verify(cache).revokeBeforeVersion(1L, Role.ADMIN, 3L);
        verify(cache).revokeBeforeVersion(1L, Role.SUPER_ADMIN, 3L);
        verify(cache).revokeIfActiveHashMatches("hash", Role.ADMIN, 1L);
    }

    @Test
    void 재사용_폐기_실패는_AUTH002다() {
        when(cache.compareAndRotate(eq(old), any(), any())).thenReturn(RotateOutcome.reuseDetected(owner));
        when(db.revokeReused(1L)).thenThrow(new DataAccessResourceFailureException("db"));
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    void 롤백이_확인되면_이번_회전에_한해_이전_캐시를_복구한다() {
        when(db.rotate(any(), any(), any(), any(), any())).thenThrow(new TransactionSystemException("rollback"));
        when(db.confirmRotation(any(), any(), any(), any()))
                .thenReturn(new AdminTokenTransactionService.RotationRecovery(null, true));
        when(cache.rollbackRotation(any(), any(), any(), any())).thenReturn(true);
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(cache).rollbackRotation(eq(TokenHasher.sha256(old)), anyString(), eq(Role.ADMIN), eq(1L));
        verify(cache, never()).revokeIfActiveHashMatches(any(), any(), any());
    }

    @Test
    void 다른_로그인으로_캐시가_바뀌면_이전_토큰을_복구하지_않는다() {
        when(db.rotate(any(), any(), any(), any(), any())).thenThrow(new TransactionSystemException("rollback"));
        when(db.confirmRotation(any(), any(), any(), any()))
                .thenReturn(new AdminTokenTransactionService.RotationRecovery(null, true));
        when(cache.rollbackRotation(any(), any(), any(), any())).thenReturn(false);
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(cache).revokeIfActiveHashMatches(anyString(), eq(Role.ADMIN), eq(1L));
    }

    @Test
    void 커밋_응답만_실패했으면_DB_확인후_성공_응답한다() {
        when(db.rotate(any(), any(), any(), any(), any())).thenThrow(new TransactionSystemException("commit reply"));
        when(db.confirmRotation(any(), any(), any(), any()))
                .thenReturn(new AdminTokenTransactionService.RotationRecovery(state, false));
        assertThat(service.reissue(old).response()).isEqualTo(state.response());
        verify(cache, never()).rollbackRotation(any(), any(), any(), any());
        verify(cache, never()).revokeIfActiveHashMatches(any(), any(), any());
    }

    @Test
    void DB_확인도_실패하면_이전_토큰을_복구하지_않고_AUTH002다() {
        when(db.rotate(any(), any(), any(), any(), any())).thenThrow(new TransactionSystemException("commit"));
        when(db.confirmRotation(any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("db"));
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(cache, never()).rollbackRotation(any(), any(), any(), any());
        verify(db).recordFailure(1L, "ROTATION_RESULT_UNKNOWN");
    }

    @Test
    void 감사_장애는_재사용_폐기와_AUTH004를_취소하지_않는다() {
        when(cache.compareAndRotate(eq(old), any(), any())).thenReturn(RotateOutcome.reuseDetected(owner));
        when(db.revokeReused(1L)).thenReturn(new AdminTokenTransactionService.RevocationState(Role.ADMIN, "hash", 3L));
        doThrow(new DataAccessResourceFailureException("audit")).when(db).recordFailure(1L, "REUSE_DETECTED");
        rejected(old, AuthErrorCode.REFRESH_TOKEN_REUSED);
        var order = inOrder(db, cache);
        order.verify(db).revokeReused(1L);
        order.verify(cache).revokeBeforeVersion(1L, Role.ADMIN, 3L);
        order.verify(cache).revokeBeforeVersion(1L, Role.SUPER_ADMIN, 3L);
        order.verify(cache).revokeIfActiveHashMatches("hash", Role.ADMIN, 1L);
        order.verify(db).recordFailure(1L, "REUSE_DETECTED");
    }

    @Test
    void 캐시_폐기_실패에도_이미_커밋된_폐기의_감사를_시도한다() {
        when(cache.compareAndRotate(eq(old), any(), any())).thenReturn(RotateOutcome.reuseDetected(owner));
        when(db.revokeReused(1L)).thenReturn(new AdminTokenTransactionService.RevocationState(Role.ADMIN, null, 3L));
        doThrow(new DataAccessResourceFailureException("cache")).when(cache).revokeBeforeVersion(any(), any(), anyLong());
        rejected(old, AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(db).recordFailure(1L, "REUSE_DETECTED");
    }

    private void rejected(String token, AuthErrorCode code) {
        assertThatThrownBy(() -> service.reissue(token)).isInstanceOfSatisfying(AuthException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }
}
