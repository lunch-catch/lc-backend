package com.launchcatch.campaign.template.dto;

import com.launchcatch.campaign.template.entity.TemplateStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record TemplatePublishResponse(
        @Schema(description = "템플릿 ID입니다.", example = "1")
        Long templateId,

        @Schema(description = "템플릿 이름입니다.", example = "가을 신메뉴")
        String name,

        @Schema(description = "템플릿 상태입니다. 게시 성공 직후는 항상 PUBLISHED입니다.", example = "PUBLISHED")
        TemplateStatus status,

        @Schema(description = "게시 시점에 확정된 HTML입니다.")
        String html,

        @Schema(description = "게시 시각입니다.")
        LocalDateTime publishedAt
) {
}
