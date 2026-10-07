package com.launchcatch.member.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.launchcatch.member.entity.AgeGroup;
import com.launchcatch.member.entity.Gender;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = false)
public record MemberOnboardingRequest(
        @NotNull Gender gender,
        @NotNull AgeGroup ageGroup,
        @NotNull Boolean locationOptIn,
        @NotNull Boolean notificationOptIn
) {
}
