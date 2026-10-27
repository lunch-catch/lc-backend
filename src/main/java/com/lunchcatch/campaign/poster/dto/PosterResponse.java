package com.lunchcatch.campaign.poster.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PosterResponse(
        @Schema(description = "캠페인 ID입니다.", example = "1")
        Long campaignId,

        @Schema(description = "저장된 포스터 HTML입니다.")
        String html
) {
}
