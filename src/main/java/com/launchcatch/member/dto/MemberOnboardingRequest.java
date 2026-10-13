package com.launchcatch.member.dto;

import com.launchcatch.member.entity.AgeGroup;
import com.launchcatch.member.entity.Gender;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "회원 온보딩 입력. 모든 필드를 지정해야 하며 성별과 연령대는 완료 후 수정할 수 없습니다.")
public record MemberOnboardingRequest(
        @Schema(description = "성별입니다.", example = "FEMALE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "성별을 입력해 주세요.") Gender gender,
        @Schema(description = "연령대입니다.", example = "AGE_20S", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "연령대를 입력해 주세요.") AgeGroup ageGroup,
        @Schema(description = "위치정보 수집 동의 여부. 거부해도 온보딩은 완료할 수 있습니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "위치정보 수집 동의 여부를 입력해 주세요.") Boolean locationOptIn,
        @Schema(description = "알림 수신 동의 여부입니다.", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "알림 수신 동의 여부를 입력해 주세요.") Boolean notificationOptIn
) {
}
