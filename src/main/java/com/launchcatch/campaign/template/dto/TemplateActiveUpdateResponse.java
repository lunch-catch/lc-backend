package com.launchcatch.campaign.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record TemplateActiveUpdateResponse(
        @Schema(description = "템플릿 ID입니다.", example = "1")
        Long templateId,

        @Schema(description = "변경된 활성 여부입니다.", example = "true")
        boolean active,

        @Schema(description = "변경한 관리자 ID입니다.", example = "7")
        Long activatedBy,

        @Schema(description = "변경한 관리자 이름입니다. 관리자를 찾을 수 없으면 null입니다.", example = "홍길동")
        String activatedByName,

        @Schema(description = "변경 시각입니다.")
        LocalDateTime activatedAt
) {
}
