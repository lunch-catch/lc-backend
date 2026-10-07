package com.launchcatch.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카카오 인가 URL 발급 응답")
public record KakaoAuthorizeResponse(
        @Schema(
                description = "state와 nonce가 포함된 카카오 인가 URL입니다. 프론트는 이 URL로 브라우저를 이동시킵니다.",
                example = "https://kauth.kakao.com/oauth/authorize?response_type=code&client_id=...&state=...&nonce=..."
        )
        String authorizationUrl
) {
}
