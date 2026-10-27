package com.lunchcatch.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "카카오 로그인 콜백에서 받은 인가 코드와 상태 검증값입니다.")
public record KakaoLoginRequest(
        @Schema(
                description = "카카오 콜백이 전달한 일회용 인가 코드입니다.",
                example = "kakao-authorization-code",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "카카오 인가 코드를 입력해 주세요.") String authorizationCode,

        @Schema(
                description = "카카오 콜백의 state입니다. 프론트가 현재 탭의 sessionStorage 값과 먼저 대조한 뒤 전달합니다.",
                example = "one-time-state",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "카카오 상태 검증값을 입력해 주세요.") String state
) {
}
