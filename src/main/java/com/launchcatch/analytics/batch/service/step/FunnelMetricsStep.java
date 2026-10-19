package com.launchcatch.analytics.batch.service.step;

import com.launchcatch.adserving.contract.AdservingStatsQueryService;
import com.launchcatch.adserving.contract.CampaignImpressionStats;
import com.launchcatch.analytics.batch.FunnelMetrics;
import com.launchcatch.analytics.batch.repository.FunnelAnalyticsRepository;
import com.launchcatch.analytics.batch.service.AggregationStep;
import com.launchcatch.analytics.event.EventType;
import com.launchcatch.analytics.event.repository.EventLogRepository;
import com.launchcatch.coupon.contract.CouponStatsQueryService;
import com.launchcatch.coupon.contract.DailyCampaignCouponStats;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 퍼널(A-03)의 일별 값을 모아 daily_funnel_analytics 의 그날 한 행으로 교체한다.
 *
 * 노출은 AdservingStatsQueryService, 발급과 사용은 CouponStatsQueryService 가 캠페인별로 세어 주고
 * 분석은 캠페인 전체를 더한다. 유효 노출은 쿠폰 소진 뒤 노출을 포함한다.
 *
 * 찜은 계약의 wishCount 를 쓰지 않고 분석 자기 event_log 의 wish 행 수를 센다. wishCount 는 31일만 보관하는
 * swipe_log 가 원천이라 31일이 지난 날짜를 다시 집계하면 저장된 찜 수를 0 으로 덮기 때문이다. event_log 는
 * 13개월 보존이라 재집계 한계와 같다. 이벤트가 유실을 허용해 아주 조금 낮을 수 있으나 찜 수는 돈과 맞아야
 * 하는 값이 아니다.
 */
@Component
@Order(400)
public class FunnelMetricsStep implements AggregationStep<FunnelMetrics> {

    private final AdservingStatsQueryService adserving;
    private final CouponStatsQueryService coupons;
    private final EventLogRepository eventLog;
    private final FunnelAnalyticsRepository repository;
    private final Clock clock;

    public FunnelMetricsStep(
            AdservingStatsQueryService adserving,
            CouponStatsQueryService coupons,
            EventLogRepository eventLog,
            FunnelAnalyticsRepository repository,
            Clock clock) {
        this.adserving = adserving;
        this.coupons = coupons;
        this.eventLog = eventLog;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "FunnelMetricsStep";
    }

    @Override
    public FunnelMetrics collect(LocalDate businessDate) {
        long valid = 0;
        long invalid = 0;
        for (CampaignImpressionStats stats : adserving.campaignStats(businessDate)) {
            valid += stats.validCount();
            invalid += stats.expiredOrUnknownCount() + stats.notOwnerCount();
        }
        long issued = 0;
        long redeemed = 0;
        for (DailyCampaignCouponStats stats : coupons.dailyCampaignStats(businessDate)) {
            issued += stats.issuedCount();
            redeemed += stats.usedCount();
        }
        return new FunnelMetrics(valid, invalid, eventLog.countByType(businessDate, EventType.WISH),
                issued, redeemed);
    }

    @Override
    public int publish(LocalDate businessDate, FunnelMetrics collected) {
        repository.replace(businessDate, collected, LocalDateTime.now(clock));
        return 1;
    }
}
