package com.launchcatch.analytics.batch;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/*
 * 하루 캠페인 리포트다. 캠페인 하나가 campaign_daily_report 한 행과 자식 표(사유, 시간대, 슬롯)의 행이 된다.
 * 사유와 시간대와 슬롯은 건수가 0이면 행이 없다.
 */
public record CampaignReport(List<Row> rows) {

    public record Row(
            long campaignId,
            long ownerId,
            long storeId,
            long dailyBudget,
            long unitPrice,
            long chargedAmount,
            long refundedAmount,
            long spentAmount,
            BigDecimal budgetSpendRate,
            long validCount,
            long billableCount,
            long notBilledCount,
            long invalidCount,
            long wishCount,
            long issuedCount,
            long redeemCount,
            Map<NotBilledReason, Long> notBilledReasons,
            Map<InvalidReasonCode, Long> invalidReasons,
            Map<Integer, Long> hourlyCounts,
            Map<SlotType, Long> slotCounts
    ) {
        public Row {
            notBilledReasons = Map.copyOf(notBilledReasons);
            invalidReasons = Map.copyOf(invalidReasons);
            hourlyCounts = Map.copyOf(hourlyCounts);
            slotCounts = Map.copyOf(slotCounts);
        }
    }

    // campaign_daily_slot_report.slot_type 에 같은 이름으로 남는다
    public enum SlotType {
        ALLOCATION,
        RELEVANCE
    }
}
