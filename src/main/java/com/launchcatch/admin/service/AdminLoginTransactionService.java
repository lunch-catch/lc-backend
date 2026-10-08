package com.launchcatch.admin.service;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
class AdminLoginTransactionService {
    private final AdminRepository adminRepository;
    private final RefreshTokenRepository refreshTokenRepository;

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
        return new LoginDbState(admin.getId(), admin.getName(), admin.getRole());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    void clearRefreshTokenIfMatches(Long adminId, String tokenHash, LocalDateTime now) {
        adminRepository.clearRefreshTokenIfMatches(adminId, tokenHash, now);
    }

    /*
     * 최초 DB 백업은 이미 커밋된 상태에서 별도 트랜잭션으로 현재 해시를 확인한다.
     * Redis 저장이 끝날 때까지 같은 행을 잠가 다른 로그인의 DB 백업이 중간에 바뀌지 않게 한다.
     * BCrypt와 JWT 생성은 이 잠금 밖에 있고, Redis 연결과 명령 제한 시간은 각각 1초다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    boolean publishRefreshTokenIfCurrent(Long adminId, Role role, String refreshToken, Duration ttl) {
        // 계정 발급과 같이 행 잠금 아래 현재 DB 상태와 권한을 확인하고 사용 불가 계정은 거부한다.
        Admin admin = adminRepository.findByIdForUpdate(adminId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_FAILED));
        if (!admin.isActive() || admin.getRole() != role) {
            throw new AuthException(AuthErrorCode.LOGIN_FAILED);
        }
        // 다른 로그인으로 교체된 경우에만 캐시 게시를 건너뛴다.
        if (!Objects.equals(admin.getRefreshTokenHash(), TokenHasher.sha256(refreshToken))) {
            return false;
        }
        try {
            refreshTokenRepository.save(refreshToken, adminId, role, true, ttl);
        } catch (DataAccessException e) {
            // 현재 DB 확인이 성공한 뒤의 Redis 저장 장애만 로그인 성공을 유지한다.
            log.warn("event=ADMIN_LOGIN_CACHE_PUBLICATION_FAILED adminId={} errorType={}",
                    adminId, e.getClass().getSimpleName());
        }
        return true;
    }

    record LoginDbState(Long adminId, String name, Role role) { }
}
