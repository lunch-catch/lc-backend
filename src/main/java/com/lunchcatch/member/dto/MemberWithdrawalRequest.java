package com.lunchcatch.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "카카오 재인증 후 탈퇴 요청입니다.")
public record MemberWithdrawalRequest(
        @Schema(description = "재인증용 카카오 로그인에서 받은 일회용 인가 코드입니다.", example = "kakao-authorization-code", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "카카오 인가 코드를 입력해 주세요.") String authorizationCode,
        @Schema(description = "재인증을 시작한 탭의 값과 대조한 상태 검증값입니다.", example = "one-time-state", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "카카오 상태 검증값을 입력해 주세요.") String state,
        @Schema(description = "선택 탈퇴 사유. 255자 이하이며 저장하지 않고 처리 후 폐기합니다.", example = "서비스를 이용하지 않습니다.", maxLength = 255, nullable = true)
        @Size(max = 255, message = "탈퇴 사유는 255자 이하로 입력해 주세요.") String reason
) {
}
