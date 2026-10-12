package com.launchcatch.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemberWithdrawalRequest(
        @NotBlank String authorizationCode,
        @NotBlank String state,
        @Size(max = 255) String reason
) {
}
