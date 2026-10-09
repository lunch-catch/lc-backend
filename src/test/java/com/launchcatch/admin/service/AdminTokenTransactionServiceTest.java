package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.entity.AdminStatus;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class AdminTokenTransactionServiceTest {
    private final AdminRepository admins = mock(AdminRepository.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final AdminTokenTransactionService service = new AdminTokenTransactionService(admins, audit);
    private final LocalDateTime now = LocalDateTime.of(2026,10,9,12,0);
    private final String old = "a".repeat(64);
    private final String next = "b".repeat(64);
    private Admin admin;

    @BeforeEach void setUp() {
        admin = Admin.register("admin", "password-hash", "관리자", Role.SUPER_ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.issueRefreshToken(old, now.plusDays(1));
        when(admins.findByIdForUpdate(1L)).thenReturn(Optional.of(admin));
        when(admins.findIdByRefreshTokenHash(old)).thenReturn(Optional.of(1L));
    }

    @Test void 회전은_현재정보와_증가한_순번을_반환하고_성공감사를_쓴다() {
        var state = service.rotate(null, old, next, now.plusDays(1), now);
        assertThat(admin.getRefreshTokenHash()).isEqualTo(next);
        assertThat(state.version()).isEqualTo(2L);
        assertThat(state.response().role()).isEqualTo(Role.SUPER_ADMIN);
        verify(audit).write(1L,"ADMIN_TOKEN_REISSUE","1","result=SUCCESS");
    }

    @ParameterizedTest @ValueSource(strings={"EXPIRED","INACTIVE","ROLE","HASH","NO_HASH","NO_EXPIRY","MISSING"})
    void 유효하지_않은_DB상태는_회전하지_않는다(String condition) {
        switch(condition) {
            case "EXPIRED" -> ReflectionTestUtils.setField(admin,"refreshTokenExpiresAt",now);
            case "INACTIVE" -> ReflectionTestUtils.setField(admin,"status",AdminStatus.DELETED);
            case "ROLE" -> ReflectionTestUtils.setField(admin,"role",Role.MEMBER);
            case "HASH" -> ReflectionTestUtils.setField(admin,"refreshTokenHash",next);
            case "NO_HASH" -> ReflectionTestUtils.setField(admin,"refreshTokenHash",null);
            case "NO_EXPIRY" -> ReflectionTestUtils.setField(admin,"refreshTokenExpiresAt",null);
            case "MISSING" -> when(admins.findByIdForUpdate(1L)).thenReturn(Optional.empty());
            default -> throw new IllegalArgumentException(condition);
        }
        assertThatThrownBy(() -> service.rotate(1L,old,next,now.plusDays(1),now)).isInstanceOf(AuthException.class);
        verifyNoInteractions(audit);
    }

    @Test void 같은_해시를_다시_회전할_수_없다() {
        service.rotate(1L,old,next,now.plusDays(1),now);
        assertThatThrownBy(() -> service.rotate(1L,old,"c".repeat(64),now.plusDays(1),now)).isInstanceOf(AuthException.class);
    }

    @Test void 재사용_폐기는_해시를_지우고_순번과_실패감사를_남긴다() {
        var state=service.revokeReused(1L);
        assertThat(admin.getRefreshTokenHash()).isNull();
        assertThat(admin.getRefreshTokenExpiresAt()).isNull();
        assertThat(state.hash()).isEqualTo(old);
        assertThat(state.version()).isEqualTo(2L);
        assertThat(state.toString()).doesNotContain(old);
        service.recordFailure(1L,"DB_FAILED");
        verify(audit).write(1L,"ADMIN_TOKEN_REISSUE","1","result=FAILURE;reason=REUSE_DETECTED");
        verify(audit).write(1L,"ADMIN_TOKEN_REISSUE","1","result=FAILURE;reason=DB_FAILED");
    }
}
