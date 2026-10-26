package com.launchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.contract.AdservingStatsQueryService;
import com.launchcatch.adserving.contract.CampaignImpressionStats;
import com.launchcatch.analytics.batch.NotBilledMetrics;
import com.launchcatch.analytics.batch.NotBilledReason;
import com.launchcatch.analytics.batch.repository.NotBilledAnalyticsRepository;
import com.launchcatch.billing.contract.BillingStatsQueryService;
import com.launchcatch.billing.contract.CampaignBillingCount;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotBilledMetricsStepTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final List<Long> HOURLY = Collections.nCopies(24, 0L);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T16:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private AdservingStatsQueryService adserving;
    @Mock
    private BillingStatsQueryService billing;
    @Mock
    private NotBilledAnalyticsRepository repository;

    private NotBilledMetricsStep step() {
        return new NotBilledMetricsStep(adserving, billing, repository, CLOCK);
    }

    private static CampaignImpressionStats stats(long campaignId, long soldOutCount) {
        return new CampaignImpressionStats(campaignId, 100, soldOutCount, 0, 0, HOURLY, Map.of(), 0);
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step().name()).isEqualTo("NotBilledMetricsStep");
    }

    @Test
    void 정산의_미과금_셋과_광고_서빙의_쿠폰_소진_뒤_노출을_캠페인마다_사유별로_합친다() {
        when(billing.billingCounts(DAY)).thenReturn(List.of(
                new CampaignBillingCount(12L, 90, 5, 3, 2)));
        when(adserving.campaignStats(DAY)).thenReturn(List.of(stats(12L, 4)));

        NotBilledMetrics metrics = step().collect(DAY);

        assertThat(metrics.rows()).containsExactly(
                new NotBilledMetrics.Row(12L, NotBilledReason.NOT_BILLED_BUDGET, 5),
                new NotBilledMetrics.Row(12L, NotBilledReason.NOT_BILLED_PAUSED, 3),
                new NotBilledMetrics.Row(12L, NotBilledReason.NOT_BILLED_NO_PLAN, 2),
                new NotBilledMetrics.Row(12L, NotBilledReason.NOT_BILLED_SOLD_OUT, 4));
    }

    @Test
    void 건수가_0인_사유는_행이_없고_과금된_노출은_세지_않는다() {
        when(billing.billingCounts(DAY)).thenReturn(List.of(
                new CampaignBillingCount(12L, 90, 0, 0, 0)));
        when(adserving.campaignStats(DAY)).thenReturn(List.of(stats(12L, 0)));

        assertThat(step().collect(DAY).rows()).isEmpty();
    }

    @Test
    void 한쪽에만_나오는_캠페인도_캠페인_번호_순으로_담는다() {
        when(billing.billingCounts(DAY)).thenReturn(List.of(
                new CampaignBillingCount(20L, 10, 1, 0, 0)));
        when(adserving.campaignStats(DAY)).thenReturn(List.of(stats(7L, 6)));

        assertThat(step().collect(DAY).rows()).containsExactly(
                new NotBilledMetrics.Row(7L, NotBilledReason.NOT_BILLED_SOLD_OUT, 6),
                new NotBilledMetrics.Row(20L, NotBilledReason.NOT_BILLED_BUDGET, 1));
    }

    @Test
    void 모으기는_자기_표를_쓰지_않는다() {
        when(billing.billingCounts(DAY)).thenReturn(List.of());
        when(adserving.campaignStats(DAY)).thenReturn(List.of());

        step().collect(DAY);

        verifyNoInteractions(repository);
    }

    @Test
    void 공개는_그날_행을_교체하고_쓴_행_수를_돌려준다() {
        NotBilledMetrics collected = new NotBilledMetrics(List.of(
                new NotBilledMetrics.Row(12L, NotBilledReason.NOT_BILLED_BUDGET, 5),
                new NotBilledMetrics.Row(12L, NotBilledReason.NOT_BILLED_SOLD_OUT, 4)));

        int written = step().publish(DAY, collected);

        assertThat(written).isEqualTo(2);
        verify(repository).replace(DAY, collected.rows(), LocalDateTime.of(2026, 10, 17, 1, 0));
    }
}
