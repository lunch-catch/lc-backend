package com.launchcatch.member.dto;

import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberStatus;
import java.time.OffsetDateTime;

public record MemberResponse(
        Long memberId,
        String nickname,
        String profileImageUrl,
        MemberStatus status,
        boolean onboardingCompleted,
        boolean feedAvailable,
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
