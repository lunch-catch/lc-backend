package com.lunchcatch.store.dto;

import com.lunchcatch.store.entity.StoreRegistrationStep;
import io.swagger.v3.oas.annotations.media.Schema;

public record StoreRegistrationProgressResponse(
        @Schema(description = "가게 ID입니다. 기본 정보 단계를 아직 안 마쳤으면 null입니다.")
        Long storeId,

        @Schema(description = "이어서 진행할 단계입니다.", example = "IMAGES")
        StoreRegistrationStep resumeStep,

        @Schema(description = "다음 단계 완료 요청에 그대로 실어 보낼 버전입니다.", example = "3")
        long version
) {
}
