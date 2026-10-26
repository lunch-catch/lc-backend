package com.launchcatch.analytics.batch.service.step;

import com.launchcatch.adserving.contract.AdservingStatsQueryService;
import com.launchcatch.adserving.contract.CampaignImpressionStats;
import com.launchcatch.analytics.batch.NotBilledMetrics;
import com.launchcatch.analytics.batch.NotBilledReason;
import com.launchcatch.analytics.batch.repository.NotBilledAnalyticsRepository;
import com.launchcatch.analytics.batch.service.AggregationStep;
import com.launchcatch.billing.contract.BillingStatsQueryService;
import com.launchcatch.billing.contract.CampaignBillingCount;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 미과금 노출의 일별 값을 모아 daily_campaign_not_billed_analytics 의 그날 행으로 교체한다.
 *
 * 예산, 중단, 예산 계획 없음, 영업일 마감은 BillingStatsQueryService 가 과금 판정으로 센다. 쿠폰 소진 뒤 노출은 정산의
 * 판정을 받지 않으므로 AdservingStatsQueryService 가 센다. 둘을 캠페인마다 사유별로 합친다.
 */
@Component
@Order(510)
public class NotBilledMetricsStep implements AggregationStep<NotBilledMetrics> {

    private final AdservingStatsQueryService adserving;
    private final BillingStatsQueryService billing;
    private final NotBilledAnalyticsRepository repository;
    private final Clock clock;

    public NotBilledMetricsStep(
            AdservingStatsQueryService adserving,
            BillingStatsQueryService billing,
            NotBilledAnalyticsRepository repository,
            Clock clock) {
        this.adserving = adserving;
        this.billing = billing;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "NotBilledMetricsStep";
    }

    @Override
    public NotBilledMetrics collect(LocalDate businessDate) {
        Map<Long, Map<NotBilledReason, Long>> byCampaign = new TreeMap<>();
        for (CampaignBillingCount count : billing.billingCounts(businessDate)) {
            add(byCampaign, count.campaignId(), NotBilledReason.NOT_BILLED_BUDGET, count.notBilledBudgetCount());
            add(byCampaign, count.campaignId(), NotBilledReason.NOT_BILLED_PAUSED, count.notBilledPausedCount());
            add(byCampaign, count.campaignId(), NotBilledReason.NOT_BILLED_NO_PLAN, count.notBilledNoPlanCount());
            add(byCampaign, count.campaignId(), NotBilledReason.NOT_BILLED_CLOSED, count.notBilledClosedCount());
        }
        for (CampaignImpressionStats stats : adserving.campaignStats(businessDate)) {
            add(byCampaign, stats.campaignId(), NotBilledReason.NOT_BILLED_SOLD_OUT, stats.soldOutCount());
        }
        List<NotBilledMetrics.Row> rows = byCampaign.entrySet().stream()
                .flatMap(campaign -> campaign.getValue().entrySet().stream()
                        .map(reason -> new NotBilledMetrics.Row(campaign.getKey(), reason.getKey(), reason.getValue())))
                .toList();
        return new NotBilledMetrics(rows);
    }

    @Override
    public int publish(LocalDate businessDate, NotBilledMetrics collected) {
        repository.replace(businessDate, collected.rows(), LocalDateTime.now(clock));
        return collected.rows().size();
    }

    private void add(Map<Long, Map<NotBilledReason, Long>> byCampaign, long campaignId, NotBilledReason reason,
            long amount) {
        if (amount > 0) {
            byCampaign.computeIfAbsent(campaignId, id -> new EnumMap<>(NotBilledReason.class)).merge(reason, amount, Long::sum);
        }
    }
}
