package com.launchcatch.member.dto;

import jakarta.validation.constraints.NotBlank;

public record KakaoLoginRequest(
        @NotBlank String authorizationCode,
        @NotBlank String state
) {
}
