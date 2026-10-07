package com.launchcatch.member.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record MemberWithdrawalRequest(
        @NotBlank String authorizationCode,
        @NotBlank String state,
        @Size(max = 255) String reason
) {
}
