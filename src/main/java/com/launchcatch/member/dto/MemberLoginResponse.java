package com.launchcatch.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원 로그인 또는 토큰 재발급 응답")
public record MemberLoginResponse(
        @Schema(description = "회원 식별자입니다.", example = "1024")
        Long memberId,

        @Schema(description = "회원 닉네임입니다.", example = "점심헌터")
        String nickname,

        @Schema(description = "이번 요청으로 신규 가입 또는 탈퇴 후 재가입 처리되었으면 true입니다.", example = "true")
        boolean newMember,

        @Schema(description = "회원 온보딩 완료 여부입니다. false면 프론트는 온보딩 화면으로 이동합니다.", example = "false")
        boolean onboardingCompleted
) {
}
