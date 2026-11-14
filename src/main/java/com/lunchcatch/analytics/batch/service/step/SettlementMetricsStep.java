package com.lunchcatch.analytics.batch.service.step;

import com.lunchcatch.analytics.batch.PendingReason;
import com.lunchcatch.analytics.batch.SettlementMetrics;
import com.lunchcatch.analytics.batch.repository.PendingDateRepository;
import com.lunchcatch.analytics.batch.repository.SettlementAnalyticsRepository;
import com.lunchcatch.analytics.batch.repository.SettlementAnalyticsRow;
import com.lunchcatch.analytics.batch.service.AggregationStep;
import com.lunchcatch.analytics.batch.service.DailyAggregationRunner;
import com.lunchcatch.billing.contract.BillingStatsQueryService;
import com.lunchcatch.billing.contract.OwnerCampaignLedgerSum;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
        return new SettlementMetrics(charge, reserve, deduct, release, refund, positiveAdjust,
                negativeAdjust, unspentBalance(businessDate), billing.correctionTargetDates(businessDate));
    }

    /*
     * 미소진 잔액은 기록 시각으로 세고 원장은 덧붙이기만 해서 끝난 날의 값이 바뀌지 않는다.
     * 그래서 가장 최근에 저장한 날의 값에 그다음 날부터 그날까지의 변동을 더해 원장 전체를 더하지 않는다.
     * 앞선 행이 하나도 없을 때만 원장 전체 합을 받는다.
     */
    private long unspentBalance(LocalDate businessDate) {
        return repository.findLatestUnspentBefore(businessDate)
                .map(latest -> latest.unspentBalance()
                        + billing.unspentChange(latest.businessDate().plusDays(1), businessDate))
                .orElseGet(() -> billing.dailyTotals(businessDate).unspentBalance());
    }

    @Override
    public int publish(LocalDate businessDate, SettlementMetrics collected) {
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<Long> previous = repository.find(businessDate).map(SettlementAnalyticsRow::unspentBalance);
        repository.replace(businessDate, toRow(collected), now);
        shiftLaterUnspent(businessDate, previous, collected.unspentBalance(), now);
        rememberCorrectionDates(businessDate, collected.correctionDates(), now);
        return 1;
    }

    /*
     * 뒤 날짜들의 미소진 잔액은 이 날의 옛 값을 이어받아 저장돼 있다.
     * 다시 집계해 값이 바뀌었으면 그 차이만큼 뒤 날짜들을 함께 고친다. 원장은 다시 읽지 않는다.
     */
    private void shiftLaterUnspent(LocalDate businessDate, Optional<Long> previous, long current, LocalDateTime now) {
        previous.filter(value -> value != current)
                .ifPresent(value -> repository.shiftUnspentAfter(businessDate, current - value, now));
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
