package com.launchcatch.member.dto;

import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.AgeGroup;
import com.launchcatch.member.entity.Gender;
import java.time.OffsetDateTime;

public record MemberResponse(
        Long memberId,
        String nickname,
        String profileImageUrl,
        MemberStatus status,
        boolean onboardingCompleted,
        Profile profile,
        Consents consents,
        OffsetDateTime createdAt
) {
    public record Profile(Gender gender, AgeGroup ageGroup) {
    }

    public record Consents(
            boolean notificationOptIn,
            OffsetDateTime notificationOptedInAt,
            boolean locationOptIn,
            OffsetDateTime locationOptedInAt
    ) {
    }
}
