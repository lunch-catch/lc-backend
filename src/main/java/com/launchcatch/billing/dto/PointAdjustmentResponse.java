package com.launchcatch.billing.dto;

import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.global.config.ClockConfig;
import java.time.OffsetDateTime;

// 기록된 조정 한 줄이며 같은 요청을 다시 받아도 처음 기록한 줄을 그대로 돌려준다
public record PointAdjustmentResponse(
        Long ledgerId,
        Long ownerId,
        long amount,
        Long campaignId,
        String reason,
        OffsetDateTime createdAt
) {

    public static PointAdjustmentResponse from(PointLedger ledger) {
        return new PointAdjustmentResponse(ledger.getId(), ledger.getOwnerId(), ledger.getAmount(),
                ledger.getCampaignId(), ledger.getReason(),
                ledger.getCreatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime());
    }
}
