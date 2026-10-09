package com.launchcatch.admin.service;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AdminLogoutTransactionService {
    private final AdminRepository admins;
    private final AuditLogWriter audit;
    private final Clock clock;

    // 로그인·재발급과 같은 행 잠금 아래 DB 폐기와 폐기 순번을 확정하고 Redis I/O는 밖에서 수행한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    LogoutDbState revokeRefreshToken(Long adminId, Role role) {
        Admin admin = admins.findByIdForUpdate(adminId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (!admin.isActive() || !role.isAdmin() || admin.getRole() != role) {
            throw new AuthException(AuthErrorCode.LOGIN_REQUIRED);
        }
        String hash = admin.getRefreshTokenHash();
        LocalDateTime cutoff = LocalDateTime.now(clock);
        admin.revokeRefreshToken();
        return new LogoutDbState(hash, admin.getRefreshTokenIssuanceVersion(), cutoff);
    }

    // 필수 토큰 폐기 완료 후 별도 트랜잭션에서 기록해 감사 저장 실패가 폐기를 롤백하지 않게 한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    void recordSuccess(Long adminId) {
        audit.write(adminId, "ADMIN_LOGOUT", String.valueOf(adminId), "result=SUCCESS");
    }

    record LogoutDbState(String hash, long version, LocalDateTime cutoff) {
        @Override
        public String toString() {
            return "LogoutDbState[hash=****, version=" + version + ", cutoff=" + cutoff + "]";
        }
    }
}
