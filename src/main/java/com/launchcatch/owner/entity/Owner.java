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

    public static Owner create(String email, String passwordHash) { return new Owner(email, passwordHash); }
}