package com.lunchcatch.admin.entity;

import com.lunchcatch.auth.Role;
import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "admin_login_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminLoginSession extends BaseTimeEntity {
    @Column(nullable = false) private Long adminId;
    @Column(nullable = false) private long issuanceVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30) private Role role;
    @Column(nullable = false, length = 64) private String refreshTokenHash;
    @Column(nullable = false) private LocalDateTime expiresAt;
    @Column(nullable = false) private long refreshTokenVersion;
    @Column(nullable = false) private boolean refreshRevoked;
    @Column(nullable = false) private boolean revoked;

    public static AdminLoginSession open(Long adminId, long version, Role role,
            String hash, LocalDateTime expiresAt) {
        if (adminId == null || adminId <= 0 || version <= 0 || role == null || !role.isAdmin()) {
            throw new IllegalArgumentException("유효한 관리자와 세션 순번이 필요하다");
        }
        AdminLoginSession session = new AdminLoginSession();
        session.adminId = adminId;
        session.issuanceVersion = version;
        session.role = role;
        session.rotate(hash, expiresAt);
        return session;
    }

    public boolean isActive(LocalDateTime now) {
        return !revoked && expiresAt.isAfter(now);
    }

    public void rotate(String hash, LocalDateTime expiresAt) {
        if (revoked || refreshRevoked || hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("활성 세션과 SHA-256 해시가 필요하다");
        }
        this.refreshTokenVersion = Math.incrementExact(refreshTokenVersion);
        this.refreshTokenHash = hash;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean isRefreshActive(LocalDateTime now) {
        return !refreshRevoked && isActive(now);
    }

    // 기존 관리자 재사용 정책대로 RT만 폐기하고 AT는 로그아웃 또는 만료까지 유지한다.
    public void revokeRefreshToken() {
        refreshRevoked = true;
        refreshTokenVersion = Math.incrementExact(refreshTokenVersion);
    }

    public void revoke() {
        revoked = true;
        revokeRefreshToken();
    }
}
