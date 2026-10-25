package com.launchcatch.analytics.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

        public Metrics metrics() {
            return new Metrics(dailyBudget, unitPrice, chargedAmount, refundedAmount, spentAmount, budgetSpendRate,
                    validCount, billableCount, notBilledCount, invalidCount, wishCount, issuedCount, redeemCount);
        }
    }

    /*
     * 리포트 본문의 공개 지표 13개다. 확정 뒤 이 중 하나라도 바뀌면 변경 이력을 남긴다(#632).
     * 소진율은 표의 자릿수(여섯 자리)로 맞춰 저장된 값과 계산한 값을 같은 값으로 비교한다.
     */
    public record Metrics(
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
            long redeemCount
    ) {
        public Metrics {
            budgetSpendRate = budgetSpendRate.setScale(6, RoundingMode.HALF_UP);
        }
    }

    // 이미 확정된 그날 리포트 행과 그 행의 마지막 변경 차수다. 이력이 없으면 차수는 0 이다
    public record Finalized(long reportId, int lastRevision, Metrics metrics) {
    }

    // campaign_daily_report_history 한 행이다
    public record Revision(long reportId, int revisionNo, ChangeReason reason, Metrics before, Metrics after) {
    }

    /*
     * campaign_daily_report_history.change_reason 에 같은 이름으로 남는다.
     * 실행 사유가 아니라 바뀐 값으로 가른다. 그래서 수동 재집계(MANUAL_RECALCULATION)는 따로 남지 않는다.
     */
    public enum ChangeReason {
        LATE_EVENT,
        OVERCHARGE_REVERSAL
    }

    // campaign_daily_slot_report.slot_type 에 같은 이름으로 남는다
    public enum SlotType {
        ALLOCATION,
        RELEVANCE
    }
}
