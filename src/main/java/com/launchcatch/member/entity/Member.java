package com.launchcatch.member.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import com.launchcatch.member.contract.MemberStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member")
@AttributeOverride(name = "id", column = @Column(name = "member_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Column(name = "provider_user_id", nullable = false, unique = true, length = 100)
    private String providerUserId;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(name = "profile_image_url", length = 512)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "refresh_token_hash", length = 64)
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    @Column(name = "notification_opt_in", nullable = false)
    private boolean notificationOptIn;

    @Column(name = "notification_opt_in_at")
    private LocalDateTime notificationOptInAt;

    @Column(name = "notification_withdrawn_at")
    private LocalDateTime notificationWithdrawnAt;

    @Column(name = "location_opt_in", nullable = false)
    private boolean locationOptIn;

    @Column(name = "location_opt_in_at")
    private LocalDateTime locationOptInAt;

    @Column(name = "location_withdrawn_at")
    private LocalDateTime locationWithdrawnAt;

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(name = "suspended_until")
    private LocalDateTime suspendedUntil;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;

    @OneToOne(mappedBy = "member", fetch = FetchType.LAZY)
    private MemberProfile profile;

    private Member(String providerUserId, String nickname, String profileImageUrl) {
        this.providerUserId = providerUserId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.status = MemberStatus.ACTIVE;
    }

    public static Member create(String providerUserId, String nickname, String profileImageUrl) {
        return new Member(providerUserId, nickname, profileImageUrl);
    }

    void attachProfile(MemberProfile profile) {
        this.profile = profile;
    }
}
