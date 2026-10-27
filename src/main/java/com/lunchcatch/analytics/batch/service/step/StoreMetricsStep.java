package com.lunchcatch.analytics.batch.service.step;

import com.lunchcatch.analytics.batch.StoreMetrics;
import com.lunchcatch.analytics.batch.repository.StoreAnalyticsRepository;
import com.lunchcatch.analytics.batch.service.AggregationStep;
import com.lunchcatch.campaign.contract.CampaignStatsQueryService;
import com.lunchcatch.store.contract.FinalizedStoreCounts;
import com.lunchcatch.store.contract.StoreStatsQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 가게 지표(A-02)의 일별 값을 모아 daily_store_analytics 의 그날 한 행으로 교체한다.
 *
 * 신규와 누적 입점은 StoreStatsQueryService 가 최종 등록 기준으로 세어 준다. 점주 상태와는 무관하다.
 * 그날 말 집행 중 캠페인 수는 CampaignStatsQueryService 가 준다.
 */
@Component
@Order(300)
public class StoreMetricsStep implements AggregationStep<StoreMetrics> {

    private final StoreStatsQueryService stores;
    private final CampaignStatsQueryService campaigns;
    private final StoreAnalyticsRepository repository;
    private final Clock clock;

    public StoreMetricsStep(
            StoreStatsQueryService stores,
            CampaignStatsQueryService campaigns,
            StoreAnalyticsRepository repository,
            Clock clock) {
        this.stores = stores;
        this.campaigns = campaigns;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "StoreMetricsStep";
    }

    @Override
    public StoreMetrics collect(LocalDate businessDate) {
        FinalizedStoreCounts counts = stores.finalizedStoreCounts(businessDate);
        return new StoreMetrics(counts.newCount(), counts.cumulativeCount(),
                campaigns.activeCampaignCount(businessDate));
    }

    @Override
    public int publish(LocalDate businessDate, StoreMetrics collected) {
        repository.replace(businessDate, collected, LocalDateTime.now(clock));
        return 1;
    }
}
