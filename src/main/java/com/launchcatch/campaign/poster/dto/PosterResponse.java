package com.launchcatch.campaign.poster.dto;

import com.launchcatch.campaign.contract.CampaignStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record PosterResponse(
        @Schema(description = "캠페인 ID입니다.", example = "1")
        Long campaignId,

        @Schema(description = "캠페인 상태입니다. ACTIVE, PAUSED, ENDED 중 하나입니다.")
        CampaignStatus status,

        @Schema(description = "저장된 포스터 HTML입니다.")
        String html
) {
}
