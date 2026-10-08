package com.launchcatch.admin.service;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AdminLoginTransactionService {
    private final AdminRepository adminRepository;

    // BCrypt와 Redis I/O를 제외한 현재 계정 확인 및 DB 백업만 짧은 트랜잭션에 둔다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    LoginDbState issueRefreshToken(
            Long adminId, String verifiedPasswordHash, String tokenHash, LocalDateTime expiresAt) {
        Admin admin = adminRepository.findByIdForUpdate(adminId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_FAILED));
        if (!admin.isActive() || !admin.getRole().isAdmin()
                || !Objects.equals(admin.getPasswordHash(), verifiedPasswordHash)) {
            throw new AuthException(AuthErrorCode.LOGIN_FAILED);
        }
        admin.issueRefreshToken(tokenHash, expiresAt);
        return new LoginDbState(admin.getId(), admin.getName(), admin.getRole(), admin.getRefreshTokenIssuanceVersion());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    void clearRefreshTokenIfMatches(Long adminId, String tokenHash, LocalDateTime now) {
        adminRepository.clearRefreshTokenIfMatches(adminId, tokenHash, now);
    }

    // 최종 상태와 토큰 해시를 짧은 트랜잭션으로 재검사하고 커밋 후 Redis 게시를 호출한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    boolean isRefreshTokenCurrent(Long adminId, Role role, String refreshToken) {
        Admin admin = adminRepository.findByIdForUpdate(adminId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_FAILED));
        if (!admin.isActive() || admin.getRole() != role) {
            throw new AuthException(AuthErrorCode.LOGIN_FAILED);
        }
        return Objects.equals(admin.getRefreshTokenHash(), TokenHasher.sha256(refreshToken));
    }

    record LoginDbState(Long adminId, String name, Role role, long issuanceVersion) { }
}
