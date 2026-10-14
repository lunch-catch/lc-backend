package com.launchcatch.admin.service;

import com.launchcatch.admin.dto.AdminLoginResponse;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AdminTokenTransactionService {
    private final AdminRepository admins;
    private final AuditLogWriter audit;

    // 로그인과 같은 행 잠금 아래 이전 해시를 비교해 DB 회전과 성공 감사를 함께 확정한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    RotationState rotate(Long idHint, String oldHash, String newHash,
            LocalDateTime expiresAt, LocalDateTime now) {
        Long id = idHint != null ? idHint : admins.findIdByRefreshTokenHash(oldHash)
                .orElseThrow(AdminTokenTransactionService::invalid);
        Admin admin = admins.findByIdForUpdate(id).orElseThrow(AdminTokenTransactionService::invalid);
        if (!admin.isActive() || !admin.getRole().isAdmin()
                || !oldHash.equals(admin.getRefreshTokenHash())
                || admin.getRefreshTokenExpiresAt() == null
                || !admin.getRefreshTokenExpiresAt().isAfter(now)) {
            throw invalid();
        }
        admin.issueRefreshToken(newHash, expiresAt);
        audit.write(id, "ADMIN_TOKEN_REISSUE", String.valueOf(id), "result=SUCCESS");
        return new RotationState(new AdminLoginResponse(id, admin.getName(), admin.getRole()),
                admin.getRefreshTokenIssuanceVersion());
    }

    // 재사용 시 DB를 먼저 폐기해 캐시 정리에 실패해도 새 재발급이 성공하지 못하게 한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    RevocationState revokeReused(Long id) {
        Admin admin = admins.findByIdForUpdate(id).orElseThrow(AdminTokenTransactionService::invalid);
        String hash = admin.getRefreshTokenHash();
        admin.revokeRefreshToken();
        return new RevocationState(admin.getRole(), hash, admin.getRefreshTokenIssuanceVersion());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    void recordFailure(Long id, String reason) {
        audit.write(id, "ADMIN_TOKEN_REISSUE", String.valueOf(id), "result=FAILURE;reason=" + reason);
    }

    // 커밋 응답 장애와 실제 롤백을 구분하며 최신 로그인 또는 폐기된 상태는 복구하지 않는다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    RotationRecovery confirmRotation(Long id, String oldHash, String newHash, LocalDateTime now) {
        Admin admin = admins.findByIdForUpdate(id).orElseThrow(AdminTokenTransactionService::invalid);
        if (!admin.isActive() || !admin.getRole().isAdmin()
                || admin.getRefreshTokenExpiresAt() == null || !admin.getRefreshTokenExpiresAt().isAfter(now)) {
            return new RotationRecovery(null, false);
        }
        if (newHash.equals(admin.getRefreshTokenHash())) {
            return new RotationRecovery(new RotationState(
                    new AdminLoginResponse(id, admin.getName(), admin.getRole()),
                    admin.getRefreshTokenIssuanceVersion()), false);
        }
        return new RotationRecovery(null, oldHash.equals(admin.getRefreshTokenHash()));
    }

    private static AuthException invalid() {
        return new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }

    record RotationRecovery(RotationState committed, boolean rolledBack) { }
    record RotationState(AdminLoginResponse response, long version) { }
    record RevocationState(Role role, String hash, long version) {
        @Override
        public String toString() { return "RevocationState[role=" + role + ", hash=****, version=" + version + "]"; }
    }
}
