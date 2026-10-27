package com.lunchcatch.admin.service;

import com.lunchcatch.admin.AdminAccessTokenClaims;
import com.lunchcatch.admin.dto.AdminLoginResponse;
import com.lunchcatch.admin.dto.AdminLoginResult;
import com.lunchcatch.admin.entity.Admin;
import com.lunchcatch.admin.entity.AdminLoginSession;
import com.lunchcatch.admin.repository.AdminRepository;
import com.lunchcatch.admin.repository.AdminLoginSessionRepository;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.auth.opaque.OpaqueTokenGenerator;
import com.lunchcatch.auth.opaque.TokenHasher;
import com.lunchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AdminSessionTransactionService {
    private final AdminRepository admins;
    private final AdminLoginSessionRepository sessions;
    private final JwtTokenProvider jwt;
    private final AuditLogWriter audit;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    SessionState issue(Long adminId, String verifiedPasswordHash) {
        Admin admin = admins.findByIdForUpdate(adminId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_FAILED));
        if (!admin.isActive() || !admin.getRole().isAdmin()
                || !Objects.equals(admin.getPasswordHash(), verifiedPasswordHash)) {
            throw new AuthException(AuthErrorCode.LOGIN_FAILED);
        }
        String token = OpaqueTokenGenerator.generate();
        AdminLoginSession session = sessions.saveAndFlush(AdminLoginSession.open(adminId,
                admin.nextSessionVersion(), admin.getRole(), TokenHasher.sha256(token), expiry(admin.getRole())));
        return state(admin, session, token);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    Optional<SessionOwner> findByHash(String hash) {
        return sessions.findByRefreshTokenHash(hash)
                .map(session -> new SessionOwner(session.getId(), session.getRole()));
    }

    // 모든 변경은 관리자 -> 세션 순서로 잠그며 Redis I/O는 커밋 밖에서 수행한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    SessionState rotate(Long sessionId, Role role, String oldHash, String token) {
        Admin admin = lockAdmin(sessionId);
        AdminLoginSession session = lockSession(sessionId);
        if (!valid(admin, session, role, LocalDateTime.now(clock))
                || !session.isRefreshActive(LocalDateTime.now(clock))
                || !oldHash.equals(session.getRefreshTokenHash())) { throw invalid(); }
        session.rotate(TokenHasher.sha256(token), expiry(role));
        audit.write(admin.getId(), "ADMIN_TOKEN_REISSUE", String.valueOf(admin.getId()), "result=SUCCESS");
        return state(admin, session, token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    Recovery confirmRotation(Long sessionId, Role role, String oldHash, String token) {
        Admin admin = lockAdmin(sessionId);
        AdminLoginSession session = lockSession(sessionId);
        if (!valid(admin, session, role, LocalDateTime.now(clock))
                || !session.isRefreshActive(LocalDateTime.now(clock))) { return new Recovery(null, false); }
        if (TokenHasher.sha256(token).equals(session.getRefreshTokenHash())) {
            return new Recovery(state(admin, session, token), false);
        }
        return new Recovery(null, oldHash.equals(session.getRefreshTokenHash()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    Revocation revokeReused(Long sessionId, Role role) {
        Admin admin = lockAdmin(sessionId);
        AdminLoginSession session = lockSession(sessionId);
        if (session.getRole() != role || !role.isAdmin()) { throw invalid(); }
        session.revokeRefreshToken();
        return revocation(admin, session);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    Optional<Revocation> logout(Long adminId, Role role, long version) {
        Admin admin = admins.findByIdForUpdate(adminId).orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        var found = sessions.findByAdminIdAndIssuanceVersion(adminId, version);
        if (found.isEmpty()) { return Optional.empty(); }
        AdminLoginSession session = sessions.findByIdForUpdate(found.get().getId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (!valid(admin, session, role, LocalDateTime.now(clock))) { throw new AuthException(AuthErrorCode.LOGIN_REQUIRED); }
        session.revoke();
        return Optional.of(revocation(admin, session));
    }

    // 계정 행을 이미 잠근 발급 재검사에서는 같은 연결을 사용해 중첩 연결 대기를 피한다.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED, timeout = 5)
    Optional<Boolean> isValid(Long adminId, Role role, long version) {
        var session = sessions.findByAdminIdAndIssuanceVersion(adminId, version);
        if (session.isEmpty()) { return Optional.empty(); }
        var admin = admins.findById(adminId);
        return Optional.of(admin.isPresent() && valid(admin.get(), session.get(), role, LocalDateTime.now(clock)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    void recordFailure(Long sessionId, String reason) {
        sessions.findAdminId(sessionId).ifPresent(adminId -> audit.write(adminId,
                "ADMIN_TOKEN_REISSUE", String.valueOf(adminId), "result=FAILURE;reason=" + reason));
    }

    private Admin lockAdmin(Long sessionId) {
        Long adminId = sessions.findAdminId(sessionId).orElseThrow(AdminSessionTransactionService::invalid);
        return admins.findByIdForUpdate(adminId).orElseThrow(AdminSessionTransactionService::invalid);
    }

    private AdminLoginSession lockSession(Long sessionId) {
        return sessions.findByIdForUpdate(sessionId).orElseThrow(AdminSessionTransactionService::invalid);
    }

    private boolean valid(Admin admin, AdminLoginSession session, Role role, LocalDateTime now) {
        return admin.isActive() && role.isAdmin() && admin.getRole() == role && session.getRole() == role
                && session.getIssuanceVersion() > admin.getAccessTokenRevokedVersion() && session.isActive(now);
    }

    private LocalDateTime expiry(Role role) {
        return LocalDateTime.now(clock).plus(Duration.ofMillis(jwt.refreshTokenValidityMs(role)));
    }

    private SessionState state(Admin admin, AdminLoginSession session, String token) {
        var result = new AdminLoginResult(new AdminLoginResponse(admin.getId(), admin.getName(), admin.getRole()),
                jwt.createAccessToken(admin.getId(), admin.getRole(), AdminAccessTokenClaims.forVersion(session.getIssuanceVersion())), token);
        return new SessionState(result, session.getId(), session.getRefreshTokenVersion(), session.getExpiresAt());
    }

    private Revocation revocation(Admin admin, AdminLoginSession session) {
        return new Revocation(admin.getId(), session.getId(), session.getRole(),
                session.getRefreshTokenHash(), session.getRefreshTokenVersion());
    }

    private static AuthException invalid() { return new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID); }
    record SessionOwner(Long sessionId, Role role) { }
    record SessionState(AdminLoginResult result, Long sessionId, long version, LocalDateTime expiresAt) { }
    record Recovery(SessionState committed, boolean rolledBack) { }
    record Revocation(Long adminId, Long sessionId, Role role, String hash, long version) {
        @Override public String toString() { return "Revocation[sessionId=" + sessionId + ", hash=****]"; }
    }
}
