package com.lunchcatch.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import com.lunchcatch.admin.dto.AdminLoginRequest;
import com.lunchcatch.admin.dto.AdminLoginResponse;
import com.lunchcatch.admin.dto.AdminLoginResult;
import com.lunchcatch.admin.entity.Admin;
import com.lunchcatch.admin.entity.AdminStatus;
import com.lunchcatch.admin.repository.AdminRepository;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AdminLoginServiceTest {
    private final AdminRepository admins = mock(AdminRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AdminSessionService sessions = mock(AdminSessionService.class);
    private final AdminLoginAuditService audit = mock(AdminLoginAuditService.class);
    private final AdminLoginRequest request = new AdminLoginRequest("admin01", "Freshman!2026");
    private AdminLoginService service;

    @BeforeEach
    void setUp() {
        when(encoder.encode(any())).thenReturn("dummy");
        service = new AdminLoginService(admins, encoder, sessions, audit);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 유효한_관리자는_별도_세션을_발급하고_성공을_기록한다(Role role) {
        var admin = admin(role);
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin));
        when(encoder.matches(request.password(), "password-hash")).thenReturn(true);
        var expected = new AdminLoginResult(new AdminLoginResponse(1L, "관리자", role), "access", "refresh");
        when(sessions.issue(1L, "password-hash")).thenReturn(expected);
        assertThat(service.login(request)).isEqualTo(expected);
        verify(audit).record(1L, true);
    }

    @Test
    void 계정이_없어도_더미_비밀번호를_비교한다() {
        failure(request);
        verify(encoder).matches(request.password(), "dummy");
        verify(audit).recordUnknownAccount();
        verifyNoInteractions(sessions);
    }

    @Test
    void 비밀번호_불일치는_같은_로그인_실패로_기록한다() {
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin(Role.ADMIN)));
        failure(request);
        verify(audit).record(1L, false, 401);
        verifyNoInteractions(sessions);
    }

    @Test
    void 비활성_계정은_비밀번호가_맞아도_실패한다() {
        var admin = admin(Role.ADMIN);
        ReflectionTestUtils.setField(admin, "status", AdminStatus.DELETED);
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin));
        when(encoder.matches(request.password(), "password-hash")).thenReturn(true);
        failure(request);
        verifyNoInteractions(sessions);
    }

    @Test
    void UTF8_72바이트를_넘으면_안전한_더미_비교후_거부한다() {
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin(Role.ADMIN)));
        failure(new AdminLoginRequest("admin01", "가".repeat(25)));
        verify(encoder).matches("invalid-overlong-password", "password-hash");
        verifyNoInteractions(sessions);
    }

    @Test
    void 세션_저장_장애는_실패_감사후_원래_오류를_전달한다() {
        when(admins.findByLoginId("admin01")).thenReturn(Optional.of(admin(Role.ADMIN)));
        when(encoder.matches(request.password(), "password-hash")).thenReturn(true);
        var failure = new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        when(sessions.issue(1L, "password-hash")).thenThrow(failure);
        assertThatThrownBy(() -> service.login(request)).isSameAs(failure);
        verify(audit).record(1L, false, 503);
    }

    private Admin admin(Role role) {
        var admin = Admin.register("admin01", "password-hash", "관리자", role);
        ReflectionTestUtils.setField(admin, "id", 1L);
        return admin;
    }

    private void failure(AdminLoginRequest request) {
        assertThatThrownBy(() -> service.login(request)).isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_FAILED);
    }
}
