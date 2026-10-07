package com.launchcatch.member.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_profile")
@AttributeOverride(name = "id", column = @Column(name = "member_profile_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberProfile extends BaseTimeEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_group", length = 10)
    private AgeGroup ageGroup;

    @Column(name = "location_nickname", length = 50)
    private String locationNickname;

    @Column(name = "road_address", length = 255)
    private String roadAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "onboarding_completed_at")
    private LocalDateTime onboardingCompletedAt;

    private MemberProfile(Member member) {
        this.member = member;
    }

    public static MemberProfile create(Member member) {
        if (member == null) {
            throw new IllegalArgumentException("member must not be null");
        }
        MemberProfile profile = new MemberProfile(member);
        member.attachProfile(profile);
        return profile;
    }

    public boolean isOnboardingCompleted() {
        return onboardingCompletedAt != null;
    }

    public void completeOnboarding(Gender gender, AgeGroup ageGroup, LocalDateTime now) {
        if (gender == null || ageGroup == null) {
            throw new IllegalArgumentException("gender and ageGroup must not be null");
        }
        this.gender = gender;
        this.ageGroup = ageGroup;
        this.onboardingCompletedAt = now;
    }
}
