package com.launchcatch.member.dto;

import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import jakarta.validation.constraints.NotNull;

public record MemberOnboardingRequest(
        @NotNull Gender gender,
        @NotNull AgeGroup ageGroup,
        @NotNull Boolean locationOptIn,
        @NotNull Boolean notificationOptIn
) {
}
