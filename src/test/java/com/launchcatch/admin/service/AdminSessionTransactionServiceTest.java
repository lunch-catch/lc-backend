package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.entity.AdminLoginSession;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.admin.repository.AdminLoginSessionRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminSessionTransactionServiceTest {
    private final AdminRepository admins = mock(AdminRepository.class);
    private final AdminLoginSessionRepository sessions = mock(AdminLoginSessionRepository.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final LocalDateTime now = LocalDateTime.now(clock);
    private final String hash = TokenHasher.sha256("old");
    private final AdminSessionTransactionService service = new AdminSessionTransactionService(admins, sessions, jwt, audit, clock);
    private Admin admin;
    private AdminLoginSession session;

    @BeforeEach
    void setUp() {
        admin = Admin.register("admin", "password", "관리자", Role.ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        session = AdminLoginSession.open(1L, admin.nextSessionVersion(), Role.ADMIN, hash, now.plusDays(1));
        ReflectionTestUtils.setField(session, "id", 10L);
        when(admins.findByIdForUpdate(1L)).thenReturn(Optional.of(admin));
        when(jwt.refreshTokenValidityMs(Role.ADMIN)).thenReturn(Duration.ofDays(1).toMillis());
        when(sessions.findAdminId(10L)).thenReturn(Optional.of(1L));
        when(sessions.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
    }

    @Test
    void 새_로그인은_다른_세션과_기존_토큰을_바꾸지_않는다() {
        when(sessions.saveAndFlush(any())).thenAnswer(call -> {
            var created = call.getArgument(0, AdminLoginSession.class);
            ReflectionTestUtils.setField(created, "id", 11L);
            return created;
        });
        var state = service.issue(1L, "password");
        assertThat(state.result().refreshToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(state.version()).isEqualTo(1);
        assertThat(session.getRefreshTokenHash()).isEqualTo(hash);
        assertThat(session.isRevoked()).isFalse();
        assertThat(admin.getRefreshTokenHash()).isNull();
    }

    @Test
    void 로그인_재검사에서_계정이_사라졌으면_AUTH001이다() {
        when(admins.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issue(1L, "password")).isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode().getCode()).isEqualTo("AUTH-001");
    }

    @Test
    void 로그아웃_재검사에서_계정이_사라졌으면_AUTH005다() {
        when(admins.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.logout(1L, Role.ADMIN, 1L)).isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode().getCode()).isEqualTo("AUTH-005");
    }

    @Test
    void 로그아웃_재검사에서_세션이_사라졌으면_AUTH005다() {
        when(sessions.findByAdminIdAndIssuanceVersion(1L, 1L)).thenReturn(Optional.of(session));
        when(sessions.findByIdForUpdate(10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.logout(1L, Role.ADMIN, 1L)).isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode().getCode()).isEqualTo("AUTH-005");
    }

    @Test
    void 잠금_후_비밀번호가_달라지면_발급하지_않는다() {
        assertThatThrownBy(() -> service.issue(1L, "stale-password")).isInstanceOf(AuthException.class);
    }

    @Test
    void DB_현재_해시로_세션을_찾아_복구한다() {
        when(sessions.findByRefreshTokenHash(hash)).thenReturn(Optional.of(session));
        assertThat(service.findByHash(hash)).contains(new AdminSessionTransactionService.SessionOwner(10L, Role.ADMIN));
        assertThat(service.findByHash("unknown")).isEmpty();
    }

    @Test
    void 회전은_세션_순번을_유지하고_캐시_게시_순번을_증가시킨다() {
        var state = service.rotate(10L, Role.ADMIN, hash, "new");
        assertThat(state.result().refreshToken()).isEqualTo("new");
        assertThat(session.getRefreshTokenHash()).isEqualTo(TokenHasher.sha256("new"));
        assertThat(session.getIssuanceVersion()).isEqualTo(1);
        assertThat(state.version()).isEqualTo(2);
        verify(audit).write(1L, "ADMIN_TOKEN_REISSUE", "1", "result=SUCCESS");
    }

    @Test
    void 이전_해시가_다르면_DB_회전은_실패한다() {
        assertThatThrownBy(() -> service.rotate(10L, Role.ADMIN, "other", "new")).isInstanceOf(AuthException.class);
        assertThat(session.getRefreshTokenHash()).isEqualTo(hash);
    }

    @Test
    void 회전_결과를_확인해_커밋과_롤백을_구분한다() {
        assertThat(service.confirmRotation(10L, Role.ADMIN, hash, "new").rolledBack()).isTrue();
        session.rotate(TokenHasher.sha256("new"), now.plusDays(1));
        assertThat(service.confirmRotation(10L, Role.ADMIN, hash, "new").committed().result().refreshToken()).isEqualTo("new");
        session.revoke();
        assertThat(service.confirmRotation(10L, Role.ADMIN, hash, "new").committed()).isNull();
    }

    @Test
    void RT_재사용은_해당_세션_RT만_폐기하고_AT는_유지한다() {
        var revoked = service.revokeReused(10L, Role.ADMIN);
        assertThat(session.isRefreshRevoked()).isTrue();
        assertThat(session.isRevoked()).isFalse();
        assertThat(session.isActive(now)).isTrue();
        assertThat(revoked.version()).isEqualTo(2);
        assertThat(revoked.toString()).doesNotContain(hash);
        assertThat(admin.getAccessTokenRevokedVersion()).isZero();
    }

    @Test
    void 세션_로그아웃은_계정_전체_폐기_기준을_바꾸지_않는다() {
        when(sessions.findByAdminIdAndIssuanceVersion(1L, 1L)).thenReturn(Optional.of(session));
        assertThat(service.logout(1L, Role.ADMIN, 1L)).isPresent();
        assertThat(session.isRevoked()).isTrue();
        assertThat(session.isRefreshRevoked()).isTrue();
        assertThat(admin.getAccessTokenRevokedVersion()).isZero();
    }

    @Test
    void 로그아웃_재검사에서_권한이_달라졌으면_기존_AUTH005로_거부한다() {
        when(sessions.findByAdminIdAndIssuanceVersion(1L, 1L)).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> service.logout(1L, Role.SUPER_ADMIN, 1L))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode().getCode()).isEqualTo("AUTH-005");
    }

    @Test
    void 세션이_없으면_기존_로그아웃을_사용한다() {
        assertThat(service.logout(1L, Role.ADMIN, 7L)).isEmpty();
    }

    @Test
    void 실패_감사는_세션이_속한_관리자_ID로_기록한다() {
        service.recordFailure(10L, "ROTATION_RESULT_UNKNOWN");
        verify(audit).write(1L, "ADMIN_TOKEN_REISSUE", "1", "result=FAILURE;reason=ROTATION_RESULT_UNKNOWN");
    }

    @Test
    void 전체_폐기와_권한_변경을_세션_검증에_반영한다() {
        when(sessions.findByAdminIdAndIssuanceVersion(1L, 1L)).thenReturn(Optional.of(session));
        when(admins.findById(1L)).thenReturn(Optional.of(admin));
        assertThat(service.isValid(1L, Role.ADMIN, 1L)).contains(true);
        assertThat(service.isValid(1L, Role.SUPER_ADMIN, 1L)).contains(false);
        admin.logoutAt(now);
        assertThat(service.isValid(1L, Role.ADMIN, 1L)).contains(false);
        assertThat(service.isValid(1L, Role.ADMIN, 99L)).isEmpty();
    }
}
