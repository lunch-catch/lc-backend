package com.launchcatch.campaign.dto;

import com.launchcatch.campaign.contract.CampaignStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record CampaignCouponConditionResponse(
        @Schema(description = "새로 만들었거나 덮어쓴 캠페인 ID입니다.", example = "501")
        Long campaignId,

        @Schema(description = "캠페인 상태입니다. 항상 DRAFT입니다.", example = "DRAFT")
        CampaignStatus status
) {
}
