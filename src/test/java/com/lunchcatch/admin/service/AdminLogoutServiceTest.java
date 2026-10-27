package com.lunchcatch.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lunchcatch.auth.Role;
import com.lunchcatch.admin.repository.AdminAccessTokenVersionRepository;
import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.auth.opaque.RefreshTokenRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

class AdminLogoutServiceTest {
    private final AdminLogoutTransactionService transactions = mock(AdminLogoutTransactionService.class);
    private final RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
    private final AccessTokenValidAfterRepository access = mock(AccessTokenValidAfterRepository.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final AdminAccessTokenVersionRepository versions = mock(AdminAccessTokenVersionRepository.class);
    private final AdminLogoutService service = new AdminLogoutService(transactions, refresh, access, jwt, versions, org.mockito.Mockito.mock(com.lunchcatch.admin.service.AdminSessionService.class));
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0, 0, 123000000);
    private final String hash = "a".repeat(64);

    @BeforeEach
    void setUp() {
        when(transactions.revokeRefreshToken(1L, Role.ADMIN))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(hash, 2, now));
        when(jwt.getAccessTokenValidityMs()).thenReturn(1800000L);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 토큰_폐기_후_성공_감사를_남긴다(Role role) {
        when(transactions.revokeRefreshToken(1L, role))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(hash, 2, now));
        service.logout(1L, role);
        verify(versions).invalidateThroughVersion(role, 1L, 2);
        verify(refresh).revokeBeforeVersion(1L, role, 2);
        verify(refresh).revokeIfActiveHashMatches(hash, role, 1L);
        verify(access).invalidateBefore(role, 1L, now, Duration.ofMinutes(30));
        verify(transactions).recordSuccess(1L);
    }

    @Test
    void DB_해시가_없는_재요청도_폐기순번으로_캐시를_정리한다() {
        when(transactions.revokeRefreshToken(1L, Role.ADMIN))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(null, 3, now));
        service.logout(1L, Role.ADMIN);
        verify(refresh).revokeBeforeVersion(1L, Role.ADMIN, 3);
        verify(refresh, never()).revokeIfActiveHashMatches(any(), any(), any());
        verify(transactions).recordSuccess(1L);
    }

    @Test
    void DB_장애는_AUTH002이며_성공감사를_남기지_않는다() {
        when(transactions.revokeRefreshToken(1L, Role.ADMIN)).thenThrow(storageFailure());
        assertUnavailable();
        verifyNoInteractions(refresh, access);
    }

    @Test
    void DB_커밋결과가_미확정이면_AUTH002다() {
        when(transactions.revokeRefreshToken(1L, Role.ADMIN)).thenThrow(new TransactionSystemException("commit"));
        assertUnavailable();
    }

    @Test
    void 폐기순번_게시_실패는_AUTH002다() {
        doThrow(storageFailure()).when(refresh).revokeBeforeVersion(1L, Role.ADMIN, 2);
        assertUnavailable();
        verifyNoInteractions(access);
    }

    @Test
    void 이전_해시_삭제_실패는_AUTH002다() {
        doThrow(storageFailure()).when(refresh).revokeIfActiveHashMatches(hash, Role.ADMIN, 1L);
        assertUnavailable();
    }

    @Test
    void Access_Token_차단_실패는_AUTH002다() {
        doThrow(storageFailure()).when(access).invalidateBefore(any(), any(), any(), any());
        assertUnavailable();
    }

    @Test
    void 감사_저장만_실패하면_로그아웃_성공을_유지한다() {
        doThrow(storageFailure()).when(transactions).recordSuccess(1L);
        assertThatCode(() -> service.logout(1L, Role.ADMIN)).doesNotThrowAnyException();
        verify(access).invalidateBefore(Role.ADMIN, 1L, now, Duration.ofMinutes(30));
    }

    @Test
    void 관리자_외_역할은_AUTH006이다() {
        assertThatThrownBy(() -> service.logout(1L, Role.MEMBER)).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED));
        verifyNoInteractions(transactions, refresh, access);
    }

    @Test
    void 관리자_없음의_인증_실패를_그대로_반환한다() {
        when(transactions.revokeRefreshToken(1L, Role.ADMIN)).thenThrow(new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        assertThatThrownBy(() -> service.logout(1L, Role.ADMIN)).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_REQUIRED));
    }

    @Test
    void Access_Token_폐기순번_저장실패는_AUTH002다() {
        doThrow(storageFailure()).when(versions).invalidateThroughVersion(Role.ADMIN, 1L, 2);
        assertUnavailable();
    }


    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 마지막_Redis_저장에_실패해도_두_JWT는_DB에서_폐기된다(Role role) {
        var admin = com.lunchcatch.admin.entity.Admin.register("logout-test", "password-hash", "관리자", role);
        admin.issueRefreshToken(hash, now.plusDays(1));
        long issuedVersion = admin.getRefreshTokenIssuanceVersion();
        when(transactions.revokeRefreshToken(1L, role)).thenAnswer(invocation -> {
            admin.logoutAt(now.plusSeconds(1));
            return new AdminLogoutTransactionService.LogoutDbState(hash,
                    admin.getAccessTokenRevokedVersion(), admin.getAccessTokenValidAfter());
        });
        doThrow(storageFailure()).when(access).invalidateBefore(any(), any(), any(), any());
        assertThatThrownBy(() -> service.logout(1L, role)).isInstanceOf(AuthException.class);
        verify(versions).invalidateThroughVersion(role, 1L, admin.getAccessTokenRevokedVersion());
        var database = mock(com.lunchcatch.admin.repository.AdminAccessTokenRevocationRepository.class);
        when(database.findRevocation(1L)).thenReturn(java.util.Optional.of(
                new com.lunchcatch.admin.AdminAccessTokenRevocation(
                        admin.getAccessTokenRevokedVersion(), admin.getAccessTokenValidAfter())));
        var verifier = new AdminAccessTokenVerifier(versions,
                new com.lunchcatch.auth.jwt.AccessTokenCutoffVerifier(access), database, org.mockito.Mockito.mock(com.lunchcatch.admin.service.AdminSessionService.class));
        assertThat(verifier.isValidAfter(role, 1L, now, issuedVersion,
                com.lunchcatch.auth.jwt.CutoffPolicy.LENIENT)).isFalse();
        assertThat(verifier.isValidAfter(role, 1L, now, null,
                com.lunchcatch.auth.jwt.CutoffPolicy.LENIENT)).isFalse();
        assertThat(admin.getRefreshTokenHash()).isNull();
        verify(transactions, never()).recordSuccess(any());
    }

    private DataAccessResourceFailureException storageFailure() {
        return new DataAccessResourceFailureException("sensitive-token-raw-or-hash");
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> service.logout(1L, Role.ADMIN)).isInstanceOfSatisfying(AuthException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
            assertThat(e.getCause()).isNull();
        });
        verify(transactions, never()).recordSuccess(any());
    }
}
