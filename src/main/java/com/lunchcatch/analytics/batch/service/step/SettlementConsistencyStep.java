package com.lunchcatch.analytics.batch.service.step;

import com.lunchcatch.analytics.batch.SettlementFindings;
import com.lunchcatch.analytics.batch.repository.SettlementAnalyticsRepository;
import com.lunchcatch.analytics.batch.service.AggregationMetrics;
import com.lunchcatch.analytics.batch.service.AggregationStep;
import com.lunchcatch.analytics.batch.service.SpendFormula;
import com.lunchcatch.billing.contract.BillingStatsQueryService;
import com.lunchcatch.billing.contract.CampaignBillingCount;
import com.lunchcatch.billing.contract.CampaignDailyBudget;
import com.lunchcatch.billing.contract.OwnerCampaignLedgerSum;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 정산 숫자가 서로 맞는지 매일 확인한다. 맞지 않아도 집계를 실패시키지 않고 고치지도 않는다. 위반
 * 수를 지표(analytics_consistency_violations{check})와 로그로 알릴 뿐이다.
 *
 *   V-1 캠페인마다 BILLED 수 x 단가 = |DEDUCT| 합
 *   V-2 캠페인마다 |DEDUCT| 합 <= |RESERVE| 합
 *   V-3 캠페인마다 RELEASE = |RESERVE| - |DEDUCT|
 *   V-7 방금 쓴 플랫폼 소진액 = 캠페인별 소진 합 - 캠페인 없는 양수 ADJUST
 *
 * V-4(점주 잔액 >= 0)는 하지 않는다. 점주별 잔액을 주는 집계 조회가 없고, 그 검사는 정산이 잔액을
 * 가진 쪽에서 해야 한다. 마지막 단계(@Order 900)로 돌아 앞 단계가 쓴 행을 같은 공개 트랜잭션에서 읽는다.
 */
@Slf4j
@Component
@Order(900)
public class SettlementConsistencyStep implements AggregationStep<SettlementFindings> {

    private static final int RESERVE = 0;
    private static final int DEDUCT = 1;
    private static final int RELEASE = 2;
    private static final int POSITIVE_ADJUST = 3;

    private final BillingStatsQueryService billing;
    private final SettlementAnalyticsRepository repository;
    private final AggregationMetrics metrics;

    public SettlementConsistencyStep(
            BillingStatsQueryService billing,
            SettlementAnalyticsRepository repository,
            AggregationMetrics metrics) {
        this.billing = billing;
        this.repository = repository;
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return "SettlementConsistencyStep";
    }

    @Override
    public SettlementFindings collect(LocalDate businessDate) {
        Map<Long, long[]> ledgerByCampaign = new HashMap<>();
        long positiveAdjustWithoutCampaign = 0;
        for (OwnerCampaignLedgerSum sum : billing.ledgerSums(businessDate)) {
            if (sum.campaignId() == null) {
                positiveAdjustWithoutCampaign += sum.positiveAdjustAmount();
                continue;
            }
            long[] ledger = ledgerByCampaign.computeIfAbsent(sum.campaignId(), id -> new long[4]);
            ledger[RESERVE] += -sum.reserveAmount();
            ledger[DEDUCT] += -sum.deductAmount();
            ledger[RELEASE] += sum.releaseAmount();
            ledger[POSITIVE_ADJUST] += sum.positiveAdjustAmount();
        }
        Map<Long, Long> billedCounts = new HashMap<>();
        for (CampaignBillingCount count : billing.billingCounts(businessDate)) {
            billedCounts.put(count.campaignId(), count.billedCount());
        }
        Map<Long, Long> unitPrices = new HashMap<>();
        for (CampaignDailyBudget budget : billing.dailyBudgets(businessDate)) {
            unitPrices.put(budget.campaignId(), budget.unitPrice());
        }

        long billedAmountViolations = 0;
        Set<Long> campaigns = new HashSet<>(ledgerByCampaign.keySet());
        campaigns.addAll(billedCounts.keySet());
        for (Long campaign : campaigns) {
            long billed = billedCounts.getOrDefault(campaign, 0L);
            long deduct = ledgerByCampaign.getOrDefault(campaign, new long[4])[DEDUCT];
            Long unitPrice = unitPrices.get(campaign);
            boolean consistent = unitPrice == null ? billed == 0 && deduct == 0 : billed * unitPrice == deduct;
            if (!consistent) {
                billedAmountViolations++;
            }
        }

        long overDeductViolations = 0;
        long releaseViolations = 0;
        long expectedSpent = -positiveAdjustWithoutCampaign;
        for (long[] ledger : ledgerByCampaign.values()) {
            if (ledger[DEDUCT] > ledger[RESERVE]) {
                overDeductViolations++;
            }
            if (ledger[RELEASE] != ledger[RESERVE] - ledger[DEDUCT]) {
                releaseViolations++;
            }
            expectedSpent += SpendFormula.spentAmount(ledger[DEDUCT], ledger[POSITIVE_ADJUST]);
        }
        return new SettlementFindings(billedAmountViolations, overDeductViolations, releaseViolations,
                expectedSpent);
    }

    @Override
    public int publish(LocalDate businessDate, SettlementFindings findings) {
        long spentViolations = repository.find(businessDate)
                .map(row -> SpendFormula.spentAmount(row.deductAmount(), row.positiveAdjustAmount())
                        == findings.expectedSpent() ? 0L : 1L)
                .orElse(1L);
        metrics.recordViolations("V-1", findings.billedAmountViolations());
        metrics.recordViolations("V-2", findings.overDeductViolations());
        metrics.recordViolations("V-3", findings.releaseViolations());
        metrics.recordViolations("V-7", spentViolations);
        if (findings.billedAmountViolations() + findings.overDeductViolations()
                + findings.releaseViolations() + spentViolations > 0) {
            log.error("event=ANALYTICS_SETTLEMENT_CONSISTENCY_MISMATCH businessDate={} v1={} v2={} v3={} v7={}",
                    businessDate, findings.billedAmountViolations(), findings.overDeductViolations(),
                    findings.releaseViolations(), spentViolations);
        }
        return 0;
    }
}
