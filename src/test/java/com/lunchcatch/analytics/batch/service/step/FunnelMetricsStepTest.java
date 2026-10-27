package com.lunchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.contract.AdservingStatsQueryService;
import com.lunchcatch.adserving.contract.CampaignImpressionStats;
import com.lunchcatch.analytics.batch.FunnelMetrics;
import com.lunchcatch.analytics.batch.repository.FunnelAnalyticsRepository;
import com.lunchcatch.analytics.event.EventType;
import com.lunchcatch.analytics.event.repository.EventLogRepository;
import com.lunchcatch.coupon.contract.CouponStatsQueryService;
import com.lunchcatch.coupon.contract.DailyCampaignCouponStats;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FunnelMetricsStepTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T16:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private AdservingStatsQueryService adserving;
    @Mock
    private CouponStatsQueryService coupons;
    @Mock
    private EventLogRepository eventLog;
    @Mock
    private FunnelAnalyticsRepository repository;

    private FunnelMetricsStep step() {
        return new FunnelMetricsStep(adserving, coupons, eventLog, repository, CLOCK);
    }

    private static CampaignImpressionStats impressions(long campaignId, long valid, long expired, long notOwner,
            long wishFromContract) {
        List<Long> hourlyCounts = new ArrayList<>(Collections.nCopies(24, 0L));
        hourlyCounts.set(10, valid);
        return new CampaignImpressionStats(campaignId, valid, 0, expired, notOwner, hourlyCounts, Map.of("ALLOCATION", (long) valid),
                wishFromContract);
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step().name()).isEqualTo("FunnelMetricsStep");
    }

    @Test
    void 캠페인별_노출을_더하고_무효는_사유_둘의_합으로_센다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of(
                impressions(1L, 1000, 7, 3, 0),
                impressions(2L, 500, 0, 2, 0)));
        when(coupons.dailyCampaignStats(DAY)).thenReturn(List.of());
        when(eventLog.countByType(DAY, EventType.WISH)).thenReturn(0L);

        FunnelMetrics metrics = step().collect(DAY);

        assertThat(metrics.validImpressionCount()).isEqualTo(1500);
        assertThat(metrics.invalidImpressionCount()).isEqualTo(12);
    }

    @Test
    void 찜은_계약의_wishCount가_아니라_이벤트_로그의_wish_행_수다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of(impressions(1L, 100, 0, 0, 999)));
        when(coupons.dailyCampaignStats(DAY)).thenReturn(List.of());
        when(eventLog.countByType(DAY, EventType.WISH)).thenReturn(150L);

        assertThat(step().collect(DAY).wishCount()).isEqualTo(150);
    }

    @Test
    void 발급과_사용은_쿠폰_집계_조회를_캠페인에_걸쳐_더한다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of());
        when(coupons.dailyCampaignStats(DAY)).thenReturn(List.of(
                new DailyCampaignCouponStats(1L, 10, 8, 5, 1),
                new DailyCampaignCouponStats(2L, 4, 4, 3, 0)));
        when(eventLog.countByType(DAY, EventType.WISH)).thenReturn(0L);

        FunnelMetrics metrics = step().collect(DAY);

        assertThat(metrics.issueCount()).isEqualTo(12);
        assertThat(metrics.redeemCount()).isEqualTo(8);
    }

    @Test
    void 그날_데이터가_하나도_없으면_모두_0이다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of());
        when(coupons.dailyCampaignStats(DAY)).thenReturn(List.of());
        when(eventLog.countByType(DAY, EventType.WISH)).thenReturn(0L);

        assertThat(step().collect(DAY)).isEqualTo(new FunnelMetrics(0, 0, 0, 0, 0));
    }

    @Test
    void 모으기는_자기_표를_쓰지_않는다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of());
        when(coupons.dailyCampaignStats(DAY)).thenReturn(List.of());
        when(eventLog.countByType(DAY, EventType.WISH)).thenReturn(0L);

        step().collect(DAY);

        verifyNoInteractions(repository);
    }

    @Test
    void 공개는_그날_행을_교체하고_쓴_행_수_1을_돌려준다() {
        FunnelMetrics collected = new FunnelMetrics(1500, 12, 150, 12, 8);

        int written = step().publish(DAY, collected);

        assertThat(written).isEqualTo(1);
        verify(repository).replace(DAY, collected, LocalDateTime.of(2026, 10, 17, 1, 0));
    }
}
