package com.lunchcatch.campaign.dto;

import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.poster.entity.ModerationFailReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CampaignActivateResponse(
        @Schema(description = "활성화를 요청한 캠페인 ID입니다.", example = "501")
        Long campaignId,

        @Schema(description = "처리 뒤의 캠페인 상태입니다. 검수를 통과하면 SCHEDULED, 실패하면 DRAFT입니다.",
                example = "SCHEDULED")
        CampaignStatus status,

        @Schema(description = "검수 실패 사유 코드 목록입니다. 통과했으면 빈 배열입니다.")
        List<ModerationFailReason> reasons
) {
}
