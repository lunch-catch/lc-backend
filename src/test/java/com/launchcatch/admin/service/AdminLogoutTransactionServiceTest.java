package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.entity.AdminStatus;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminLogoutTransactionServiceTest {
    private final AdminRepository admins = mock(AdminRepository.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T03:00:00.123Z"), ZoneOffset.UTC);
    private final AdminLogoutTransactionService service = new AdminLogoutTransactionService(admins, audit, clock);
    private Admin admin;

    @BeforeEach
    void setUp() {
        admin = Admin.register("admin01", "password-hash", "관리자", Role.ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.issueRefreshToken("a".repeat(64), LocalDateTime.now(clock).plusDays(1));
        when(admins.findByIdForUpdate(1L)).thenReturn(Optional.of(admin));
    }

    @Test
    void DB_해시와_만료를_지우고_증가된_폐기순번과_시각을_반환한다() {
        var state = service.revokeRefreshToken(1L, Role.ADMIN);
        assertThat(admin.getRefreshTokenHash()).isNull();
        assertThat(admin.getRefreshTokenExpiresAt()).isNull();
        assertThat(state.hash()).isEqualTo("a".repeat(64));
        assertThat(state.version()).isEqualTo(2);
        assertThat(state.cutoff()).isEqualTo(LocalDateTime.now(clock));
        assertThat(state.toString()).doesNotContain("a".repeat(64));
        verifyNoInteractions(audit);
    }

    @Test
    void 이미_DB에서_폐기된_재요청도_새_폐기순번으로_정리한다() {
        admin.revokeRefreshToken();
        var state = service.revokeRefreshToken(1L, Role.ADMIN);
        assertThat(state.hash()).isNull();
        assertThat(state.version()).isEqualTo(3);
    }

    @Test
    void 없는_관리자는_AUTH005다() {
        when(admins.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertRejected(Role.ADMIN);
    }

    @Test
    void 비활성_관리자는_AUTH005다() {
        ReflectionTestUtils.setField(admin, "status", AdminStatus.DELETED);
        assertRejected(Role.ADMIN);
        assertThat(admin.getRefreshTokenHash()).isNotNull();
    }

    @Test
    void 현재_DB와_역할이_다르면_AUTH005다() { assertRejected(Role.SUPER_ADMIN); }

    @Test
    void 관리자_외_역할은_DB를_변경하지_않는다() { assertRejected(Role.OWNER); }

    @Test
    void 성공_감사는_자격증명_없이_기록한다() {
        service.recordSuccess(1L);
        verify(audit).write(1L, "ADMIN_LOGOUT", "1", "result=SUCCESS");
    }

    private void assertRejected(Role role) {
        assertThatThrownBy(() -> service.revokeRefreshToken(1L, role))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_REQUIRED));
    }
}
