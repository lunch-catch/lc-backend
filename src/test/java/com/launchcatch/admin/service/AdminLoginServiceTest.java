package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.admin.dto.AdminLoginRequest;
import com.launchcatch.admin.dto.AdminLoginResult;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.entity.AdminStatus;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;

class AdminLoginServiceTest {
    private final AdminRepository admins = mock(AdminRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final AdminLoginTransactionService transactions = mock(AdminLoginTransactionService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final AdminLoginRequest request = new AdminLoginRequest("admin01", "Freshman!2026");
    private AdminLoginService service;

    @BeforeEach
    void setUp() {
        when(encoder.encode(any())).thenReturn("dummy-hash");
        service = new AdminLoginService(admins, encoder, jwt, transactions, clock);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 활성_관리자에게_명세대로_토큰을_발급하고_같은_해시를_백업한다(Role role) {
        prepare(role);
        AdminLoginResult result = service.login(request);
        assertThat(result.response().adminId()).isEqualTo(1L);
        assertThat(result.response().role()).isEqualTo(role);
        assertThat(result.response().name()).isEqualTo("관리자");
        assertThat(result.accessToken()).isEqualTo("signed-access-token");
        assertThat(result.refreshToken()).matches("[A-Za-z0-9_-]{43}");
        verify(transactions).issueRefreshToken(1L, "password-hash", TokenHasher.sha256(result.refreshToken()),
                LocalDateTime.now(clock).plusDays(1));
        verify(transactions).publishRefreshTokenIfCurrent(1L, role, result.refreshToken(), Duration.ofDays(1));
    }

    @Test
    void 계정이_없어도_더미_비밀번호를_비교하고_AUTH001을_반환한다() {
        when(admins.findByLoginId("admin01")).thenReturn(Optional.empty());
        assertFailure(AuthErrorCode.LOGIN_FAILED);
        verify(encoder).matches(request.password(), "dummy-hash");
        verifyNoInteractions(transactions, jwt);
    }

    @Test
    void 더미_비밀번호가_우연히_일치해도_계정이_없으면_거부한다() {
        when(admins.findByLoginId("admin01")).thenReturn(Optional.empty());
        when(encoder.matches(request.password(), "dummy-hash")).thenReturn(true);
        assertFailure(AuthErrorCode.LOGIN_FAILED);
        verifyNoInteractions(transactions, jwt);
    }

    @Test
    void 비밀번호가_틀리면_같은_AUTH001로_거부한다() {
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin(Role.ADMIN)));
        assertFailure(AuthErrorCode.LOGIN_FAILED);
        verifyNoInteractions(transactions, jwt);
    }

    @Test
    void 비활성_계정도_비밀번호_비교후_같은_AUTH001로_거부한다() {
        Admin admin = admin(Role.ADMIN);
        ReflectionTestUtils.setField(admin, "status", AdminStatus.DELETED);
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin));
        when(encoder.matches(request.password(), "password-hash")).thenReturn(true);
        assertFailure(AuthErrorCode.LOGIN_FAILED);
        verifyNoInteractions(transactions, jwt);
    }

    @Test
    void UTF8_72바이트를_넘는_입력은_인코더_예외없이_AUTH001로_거부한다() {
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin(Role.ADMIN)));
        AdminLoginRequest tooLong = new AdminLoginRequest("admin01", "가".repeat(25));
        assertThatThrownBy(() -> service.login(tooLong)).isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_FAILED);
        verify(encoder).matches("invalid-overlong-password", "password-hash");
        verifyNoInteractions(transactions, jwt);
    }

    @Test
    void DB_백업_실패는_AUTH002이고_토큰을_캐시에_저장하지_않는다() {
        prepare(Role.ADMIN);
        when(transactions.issueRefreshToken(eq(1L), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("DB unavailable"));
        assertFailure(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(transactions, never()).publishRefreshTokenIfCurrent(any(), any(), any(), any());
    }

    @Test
    void 트랜잭션_생성_실패도_AUTH002이다() {
        prepare(Role.ADMIN);
        when(transactions.issueRefreshToken(eq(1L), any(), any(), any()))
                .thenThrow(new CannotCreateTransactionException("DB unavailable"));
        assertFailure(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(transactions, never()).publishRefreshTokenIfCurrent(any(), any(), any(), any());
    }

    @Test
    void 잠금후_계정_상태가_바뀌면_로그인에_실패한다() {
        prepare(Role.ADMIN);
        when(transactions.issueRefreshToken(eq(1L), any(), any(), any()))
                .thenThrow(new AuthException(AuthErrorCode.LOGIN_FAILED));
        assertFailure(AuthErrorCode.LOGIN_FAILED);
        verify(transactions, never()).publishRefreshTokenIfCurrent(any(), any(), any(), any());
    }

    @Test
    void 최종_DB_조회_장애는_AUTH002이며_이번_백업을_정리한다() {
        prepare(Role.ADMIN);
        doThrow(new DataAccessResourceFailureException("DB unavailable"))
                .when(transactions).publishRefreshTokenIfCurrent(eq(1L), eq(Role.ADMIN), any(), any());
        assertFailure(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(transactions).clearRefreshTokenIfMatches(eq(1L), any(), eq(LocalDateTime.now(clock)));
    }

    @Test
    void 최종_트랜잭션_실패는_AUTH002이며_이번_백업을_정리한다() {
        prepare(Role.ADMIN);
        when(transactions.publishRefreshTokenIfCurrent(eq(1L), any(), any(), any()))
                .thenThrow(new CannotCreateTransactionException("DB unavailable during publication"));
        assertFailure(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verify(transactions).clearRefreshTokenIfMatches(eq(1L), any(), eq(LocalDateTime.now(clock)));
    }

    @Test
    void 다른_로그인으로_교체된_토큰은_캐시에_게시하지_않는다() {
        prepare(Role.ADMIN);
        when(transactions.publishRefreshTokenIfCurrent(eq(1L), any(), any(), any())).thenReturn(false);
        assertThat(service.login(request).response().adminId()).isEqualTo(1L);
    }

    @Test
    void 토큰은_잠금후_확인한_최신_권한으로_발급한다() {
        prepare(Role.SUPER_ADMIN);
        when(transactions.issueRefreshToken(eq(1L), any(), any(), any()))
                .thenReturn(new AdminLoginTransactionService.LoginDbState(1L, "관리자", Role.ADMIN));
        when(jwt.createAccessToken(1L, Role.ADMIN)).thenReturn("admin-token");
        assertThat(service.login(request).response().role()).isEqualTo(Role.ADMIN);
        verify(jwt).createAccessToken(1L, Role.ADMIN);
    }

    @Test
    void JWT_생성_실패는_이번_DB_백업만_조건부_제거한다() {
        prepare(Role.ADMIN);
        when(jwt.createAccessToken(1L, Role.ADMIN)).thenThrow(new IllegalStateException("signing failed"));
        assertThatThrownBy(() -> service.login(request)).isInstanceOf(IllegalStateException.class);
        verify(transactions).clearRefreshTokenIfMatches(eq(1L), any(), eq(LocalDateTime.now(clock)));
        verify(transactions, never()).publishRefreshTokenIfCurrent(any(), any(), any(), any());
    }

    @Test
    void JWT_실패후_보상_실패는_원래_오류에_첨부한다() {
        prepare(Role.ADMIN);
        var failure = new IllegalStateException("signing failed");
        var cleanup = new DataAccessResourceFailureException("cleanup failed");
        when(jwt.createAccessToken(1L, Role.ADMIN)).thenThrow(failure);
        doThrow(cleanup).when(transactions).clearRefreshTokenIfMatches(eq(1L), any(), any());
        assertThatThrownBy(() -> service.login(request)).isSameAs(failure).hasSuppressedException(cleanup);
    }

    @Test
    void 최종_계정_검사_거부는_이번_해시만_정리하고_AUTH001을_유지한다() {
        prepare(Role.ADMIN);
        var rejected = new AuthException(AuthErrorCode.LOGIN_FAILED);
        when(transactions.publishRefreshTokenIfCurrent(eq(1L), any(), any(), any())).thenThrow(rejected);
        var hash = org.mockito.ArgumentCaptor.forClass(String.class);
        assertThatThrownBy(() -> service.login(request)).isSameAs(rejected);
        verify(transactions).issueRefreshToken(eq(1L), any(), hash.capture(), any());
        verify(transactions).clearRefreshTokenIfMatches(1L, hash.getValue(), LocalDateTime.now(clock));
    }

    @Test
    void 거부후_보상_실패도_로그인을_성공으로_바꾸지_않는다() {
        prepare(Role.ADMIN);
        var rejected = new AuthException(AuthErrorCode.LOGIN_FAILED);
        var cleanup = new DataAccessResourceFailureException("cleanup failed");
        when(transactions.publishRefreshTokenIfCurrent(eq(1L), any(), any(), any())).thenThrow(rejected);
        doThrow(cleanup).when(transactions).clearRefreshTokenIfMatches(eq(1L), any(), any());
        assertThatThrownBy(() -> service.login(request)).isSameAs(rejected).hasSuppressedException(cleanup);
    }

    private void prepare(Role role) {
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin(role)));
        when(encoder.matches(request.password(), "password-hash")).thenReturn(true);
        when(jwt.refreshTokenValidityMs(role)).thenReturn(Duration.ofDays(1).toMillis());
        when(transactions.issueRefreshToken(eq(1L), any(), any(), any()))
                .thenReturn(new AdminLoginTransactionService.LoginDbState(1L, "관리자", role));
        when(jwt.createAccessToken(1L, role)).thenReturn("signed-access-token");
        when(transactions.publishRefreshTokenIfCurrent(eq(1L), any(), any(), any())).thenReturn(true);
    }

    private Admin admin(Role role) {
        Admin admin = Admin.register("admin01", "password-hash", "관리자", role);
        ReflectionTestUtils.setField(admin, "id", 1L);
        return admin;
    }

    private void assertFailure(AuthErrorCode expected) {
        assertThatThrownBy(() -> service.login(request)).isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(expected);
    }
}
