package com.launchcatch.owner.entity;

import com.launchcatch.auth.Role;
import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "owner")
@AttributeOverride(name = "id", column = @Column(name = "owner_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Owner extends BaseTimeEntity {

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnerStatus status;

    @Column(name = "tutorial_viewed", nullable = false)
    private boolean tutorialViewed;

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "refresh_token_hash", length = 64)
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // 점주 행 잠금 아래 증가시키며 Redis RT 게시 순서를 판단한다.
    @Column(name = "refresh_token_issuance_version", nullable = false)
    private long refreshTokenIssuanceVersion;

    private Owner(String email, String passwordHash) {
        if (email == null || email.isBlank() || email.length() > 255) {
            throw new IllegalArgumentException("점주 이메일은 필수이며, 255자 이하여야 합니다.");
        }

        if (passwordHash == null || passwordHash.isBlank() || passwordHash.length() > 255) {
            throw new IllegalArgumentException("점주 비밀번호 해시는 필수이며, 255자 이하여야 합니다.");
        }

        this.email = email;
        this.passwordHash = passwordHash;
        this.role = Role.OWNER;
        this.status = OwnerStatus.ONBOARDING;
        this.tutorialViewed = false;
    }

    public boolean canLogin() {
        return status == OwnerStatus.ONBOARDING || status == OwnerStatus.ACTIVE;
    }

    public void recordLogin(String refreshTokenHash, LocalDateTime expiresAt, LocalDateTime loginAt) {
        if (!canLogin()) {
            throw new IllegalStateException("현재 점주 상태에서는 로그인할 수 없습니다.");
        }
        if (refreshTokenHash == null || !refreshTokenHash.matches("[0-9a-f]{64}")
                || expiresAt == null || loginAt == null || !expiresAt.isAfter(loginAt)) {
            throw new IllegalArgumentException("Refresh Token 해시와 유효한 만료 시각이 필요합니다.");
        }
        this.refreshTokenIssuanceVersion = Math.incrementExact(refreshTokenIssuanceVersion);
        this.refreshTokenHash = refreshTokenHash;
        this.refreshTokenExpiresAt = expiresAt;
        this.lastLoginAt = loginAt;
    }

    public static Owner create(String email, String passwordHash) { return new Owner(email, passwordHash); }
}