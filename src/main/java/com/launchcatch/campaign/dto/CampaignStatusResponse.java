package com.launchcatch.campaign.dto;

import com.launchcatch.campaign.contract.CampaignStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record CampaignStatusResponse(
        @Schema(description = "중단 또는 재개한 캠페인 ID입니다.", example = "501")
        Long campaignId,

        @Schema(description = "처리 뒤의 캠페인 상태입니다.", example = "PAUSED")
        CampaignStatus status
) {
}
