package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.launchcatch.admin.dto.AdminRegistrationRequest;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.entity.AdminStatus;
import com.launchcatch.admin.exception.AdminErrorCode;
import com.launchcatch.admin.exception.AdminException;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.AccessTokenCutoffVerifier;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import java.time.LocalDateTime;
import org.springframework.dao.DataAccessResourceFailureException;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AdminRegistrationServiceTest {
    private final AdminRepository repository = mock(AdminRepository.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 8, 10, 0);
    private final AccessTokenValidAfterRepository cutoff = mock(AccessTokenValidAfterRepository.class);
    private final AdminRegistrationService service =
            new AdminRegistrationService(repository, audit, encoder, transactions, new AccessTokenCutoffVerifier(cutoff));

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(transaction);
        when(cutoff.isValidAfter(Role.SUPER_ADMIN, 1L, ISSUED_AT)).thenReturn(true);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 최고관리자는_두_권한의_계정을_발급한다(Role role) {
        givenActiveSuperAdminIssuer();
        var request = request(role.name());
        when(repository.saveAndFlush(any(Admin.class))).thenAnswer(i -> i.getArgument(0));
        var response = service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request);
        assertThat(response.loginId()).isEqualTo(request.loginId());
        assertThat(response.name()).isEqualTo(request.name());
        assertThat(response.role()).isEqualTo(role.name());
        var captor = ArgumentCaptor.forClass(Admin.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(role);
        assertThat(captor.getValue().getStatus()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(encoder.matches(request.initialPassword(), captor.getValue().getPasswordHash())).isTrue();
        verify(audit).write(1L, "ADMIN_ACCOUNT_CREATE", request.loginId(), "role=" + role.name());
        var order = inOrder(encoder, transactions, repository, audit);
        order.verify(encoder).encode(request.initialPassword());
        order.verify(transactions).getTransaction(any());
        order.verify(repository).findByIdForUpdate(1L);
        order.verify(repository).saveAndFlush(any());
        order.verify(audit).write(1L, "ADMIN_ACCOUNT_CREATE", request.loginId(), "role=" + role.name());
        order.verify(transactions).commit(transaction);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "OWNER", "MEMBER"})
    void 최고관리자_외에는_거부한다(Role role) {
        assertThatThrownBy(() -> service.register(1L, role, ISSUED_AT, request("ADMIN")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED);
        verifyNoInteractions(repository, audit, encoder);
    }

    @Test
    void 중복이면_해싱과_저장을_하지_않는다() {
        when(repository.findByLoginId("admin01")).thenReturn(Optional.of(mock(Admin.class)));
        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN")))
                .isInstanceOf(AdminException.class);
        verifyNoInteractions(encoder, audit);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void DB_ID_유니크_위반만_중복으로_변환한다() {
        givenActiveSuperAdminIssuer();
        var cause = new DataIntegrityViolationException("duplicate uk_admin_login_id");
        when(repository.saveAndFlush(any())).thenThrow(cause);
        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN")))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode()).isEqualTo(AdminErrorCode.LOGIN_ID_DUPLICATED);
        verify(transactions).rollback(transaction);
        verifyNoInteractions(audit);
    }

    @Test
    void 다른_DB_제약_오류는_중복으로_변환하지_않는다() {
        givenActiveSuperAdminIssuer();
        var cause = new DataIntegrityViolationException("chk_admin_role");
        when(repository.saveAndFlush(any())).thenThrow(cause);
        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN"))).isSameAs(cause);
        verify(transactions).rollback(transaction);
    }

    @Test
    void 감사_로그_실패는_동일_트랜잭션을_롤백한다() {
        givenActiveSuperAdminIssuer();
        when(repository.saveAndFlush(any(Admin.class))).thenAnswer(i -> i.getArgument(0));
        var cause = new DataIntegrityViolationException("audit_log write failed");
        doThrow(cause).when(audit).write(any(), any(), any(), any());
        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN"))).isSameAs(cause);
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    @Test
    void 해싱_실패_시_쓰기_트랜잭션을_시작하지_않는다() {
        doThrow(new IllegalArgumentException("encoding failed")).when(encoder).encode(any());
        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(transactions, never()).getTransaction(any());
        verifyNoInteractions(audit);
    }

    @Test
    void DB에_발급자가_없으면_거부한다() {
        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED);
        verify(repository).findByIdForUpdate(1L);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "OWNER", "MEMBER"})
    void DB의_현재_권한이_최고관리자가_아니면_거부한다(Role role) {
        Admin issuer = mock(Admin.class);
        when(issuer.getStatus()).thenReturn(AdminStatus.ACTIVE);
        when(issuer.getRole()).thenReturn(role);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(issuer));

        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    @ParameterizedTest
    @EnumSource(value = AdminStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void DB의_발급자가_비활성_상태이면_거부한다(AdminStatus status) {
        Admin issuer = mock(Admin.class);
        when(issuer.getStatus()).thenReturn(status);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(issuer));

        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    @Test
    void 발급자_잠금_조회_실패는_그대로_전달하고_저장하지_않는다() {
        var cause = new org.springframework.dao.CannotAcquireLockException("issuer lock failed");
        when(repository.findByIdForUpdate(1L)).thenThrow(cause);

        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN"))).isSameAs(cause);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    @Test
    void 폐기_확인_장애는_AUTH002로_변환하고_발급을_시작하지_않는다() {
        when(cutoff.isValidAfter(Role.SUPER_ADMIN, 1L, ISSUED_AT))
                .thenThrow(new DataAccessResourceFailureException("Redis unavailable"));
        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        verifyNoInteractions(repository, audit, encoder);
        verify(transactions, never()).getTransaction(any());
    }

    @Test
    void 폐기된_토큰은_AUTH005로_거부한다() {
        when(cutoff.isValidAfter(Role.SUPER_ADMIN, 1L, ISSUED_AT)).thenReturn(false);
        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, ISSUED_AT, request("ADMIN")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_REQUIRED);
        verifyNoInteractions(repository, audit, encoder);
    }

    @Test
    void 발급_시각이_없으면_거부한다() {
        assertThatThrownBy(() -> service.register(1L, Role.SUPER_ADMIN, null, request("ADMIN")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_REQUIRED);
        verifyNoInteractions(repository, audit, encoder);
    }

    private void givenActiveSuperAdminIssuer() {
        Admin issuer = Admin.register("superadmin", "password-hash", "최고관리자", Role.SUPER_ADMIN);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(issuer));
    }

    private static AdminRegistrationRequest request(String role) {
        return new AdminRegistrationRequest("admin01", "Initial123!", "홍길동", role);
    }
}