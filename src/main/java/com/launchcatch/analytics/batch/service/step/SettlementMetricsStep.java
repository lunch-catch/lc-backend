package com.launchcatch.analytics.batch.service.step;

import com.launchcatch.analytics.batch.PendingReason;
import com.launchcatch.analytics.batch.SettlementMetrics;
import com.launchcatch.analytics.batch.repository.PendingDateRepository;
import com.launchcatch.analytics.batch.repository.SettlementAnalyticsRepository;
import com.launchcatch.analytics.batch.repository.SettlementAnalyticsRow;
import com.launchcatch.analytics.batch.service.AggregationStep;
import com.launchcatch.analytics.batch.service.DailyAggregationRunner;
import com.launchcatch.billing.contract.BillingStatsQueryService;
import com.launchcatch.billing.contract.DailyLedgerTotals;
import com.launchcatch.billing.contract.OwnerCampaignLedgerSum;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 매출(A-07)의 일별 지표를 모아 daily_settlement_analytics 의 그날 한 행으로 교체한다.
 *
 * 정산 원장의 합계는 BillingStatsQueryService 가 영업일 귀속 규칙으로 묶어 준다. 분석은 점주와 캠페인
 * 축을 합쳐 플랫폼 전체 하루 합으로 만든다. 표의 CHECK 가 금액 열을 0 이상으로 묶어서 RESERVE, DEDUCT,
 * REFUND 는 절댓값으로 저장하고 조정액(adjust_amount)만 ±합을 그대로 둔다.
 *
 * 모으기는 부작용이 없어야 하므로 정정 대상 영업일을 미처리 목록에 남기는 일은 공개에서 한다. 정정이
 * 가리키는 날은 이미 확정해 둔 과거 날짜라 다음 실행에서 다시 집계되어야 한다.
 */
@Component
@Order(100)
public class SettlementMetricsStep implements AggregationStep<SettlementMetrics> {

    private final BillingStatsQueryService billing;
    private final SettlementAnalyticsRepository repository;
    private final PendingDateRepository pending;
    private final Clock clock;

    public SettlementMetricsStep(
            BillingStatsQueryService billing,
            SettlementAnalyticsRepository repository,
            PendingDateRepository pending,
            Clock clock) {
        this.billing = billing;
        this.repository = repository;
        this.pending = pending;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "SettlementMetricsStep";
    }

    @Override
    public SettlementMetrics collect(LocalDate businessDate) {
        long charge = 0;
        long reserve = 0;
        long deduct = 0;
        long release = 0;
        long refund = 0;
        long positiveAdjust = 0;
        long negativeAdjust = 0;
        for (OwnerCampaignLedgerSum sum : billing.ledgerSums(businessDate)) {
            charge += sum.chargeAmount();
            reserve += sum.reserveAmount();
            deduct += sum.deductAmount();
            release += sum.releaseAmount();
            refund += sum.refundAmount();
            positiveAdjust += sum.positiveAdjustAmount();
            negativeAdjust += sum.negativeAdjustAmount();
        }
        DailyLedgerTotals totals = billing.dailyTotals(businessDate);
        return new SettlementMetrics(charge, reserve, deduct, release, refund, positiveAdjust,
                negativeAdjust, totals.unspentBalance(), billing.correctionTargetDates(businessDate));
    }

    @Override
    public int publish(LocalDate businessDate, SettlementMetrics collected) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.replace(businessDate, toRow(collected), now);
        rememberCorrectionDates(businessDate, collected.correctionDates(), now);
        return 1;
    }

    private SettlementAnalyticsRow toRow(SettlementMetrics metrics) {
        return new SettlementAnalyticsRow(
                metrics.charge(),
                -metrics.deduct(),
                -metrics.reserve(),
                metrics.release(),
                -metrics.refund(),
                metrics.positiveAdjust() + metrics.negativeAdjust(),
                metrics.positiveAdjust(),
                metrics.unspentBalance());
    }

    /*
     * 이미 집계하는 날짜 자신과 오늘 이후, 보존 기간 밖 날짜는 남기지 않는다. 보존 기간 밖은 원본이 없어
     * 다시 집계하면 0 으로 덮이기 때문이다.
     */
    private void rememberCorrectionDates(LocalDate businessDate, List<LocalDate> dates, LocalDateTime now) {
        LocalDate today = LocalDate.now(clock);
        LocalDate oldestRetained = today.minusMonths(DailyAggregationRunner.RETENTION_MONTHS);
        for (LocalDate date : dates) {
            if (!date.equals(businessDate) && date.isBefore(today) && !date.isBefore(oldestRetained)) {
                pending.record(date, PendingReason.ADJUSTED, now);
            }
        }
    }
}
