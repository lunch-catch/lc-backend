package com.lunchcatch.campaign.dto;

import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.entity.ActorType;
import com.lunchcatch.campaign.entity.CampaignStatusLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record AdminCampaignStatusLogResponse(
        @Schema(description = "바뀌기 전 상태입니다.", example = "SCHEDULED")
        CampaignStatus fromStatus,

        @Schema(description = "바뀐 뒤 상태입니다.", example = "ACTIVE")
        CampaignStatus toStatus,

        @Schema(description = "상태를 바꾼 주체입니다.", example = "SYSTEM")
        ActorType actorType,

        @Schema(description = "상태를 바꾼 사유입니다. 없으면 null입니다.")
        String reason,

        @Schema(description = "상태가 바뀐 시각입니다.")
        LocalDateTime changedAt
) {
    public static AdminCampaignStatusLogResponse from(CampaignStatusLog log) {
        return new AdminCampaignStatusLogResponse(
                log.getFromStatus(), log.getToStatus(), log.getActorType(), log.getReason(), log.getCreatedAt());
    }
}
