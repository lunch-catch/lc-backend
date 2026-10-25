package com.launchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.contract.AdservingStatsQueryService;
import com.launchcatch.adserving.contract.CampaignImpressionStats;
import com.launchcatch.adserving.contract.CampaignMemberConcentration;
import com.launchcatch.analytics.batch.FraudMetrics;
import com.launchcatch.analytics.batch.repository.FraudAnalyticsRepository;
import com.launchcatch.analytics.config.AnalyticsFraudProperties;
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
class FraudMetricsStepTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final List<Long> HOURLY = Collections.nCopies(24, 0L);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T16:00:00Z"),
            ZoneId.of("Asia/Seoul"));
    private static final AnalyticsFraudProperties PROPERTIES = new AnalyticsFraudProperties(5, 0.5, 100, 100);

    @Mock
    private AdservingStatsQueryService adserving;
    @Mock
    private FraudAnalyticsRepository repository;

    private FraudMetricsStep step() {
        return new FraudMetricsStep(adserving, PROPERTIES, repository, CLOCK);
    }

    private static CampaignImpressionStats stats(long campaignId, long valid, long expired, long notOwner) {
        return new CampaignImpressionStats(campaignId, valid, 0, expired, notOwner, HOURLY, Map.of(), 0);
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step().name()).isEqualTo("FraudMetricsStep");
    }

    @Test
    void 캠페인마다_유효와_무효_노출과_회원_집중을_한_행으로_합친다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of(stats(12L, 4_000, 800, 300)));
        when(adserving.memberConcentrations(DAY, 5)).thenReturn(List.of(
                new CampaignMemberConcentration(12L, 4, 3_680)));

        FraudMetrics metrics = step().collect(DAY);

        assertThat(metrics.rows()).containsExactly(new FraudMetrics.Row(12L, 4_000, 1_100, 4, 3_680, true));
    }

    @Test
    void 상위_회원_수는_설정값으로_집계_조회에_넘긴다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of());
        when(adserving.memberConcentrations(DAY, 5)).thenReturn(List.of());

        step().collect(DAY);

        verify(adserving).memberConcentrations(DAY, 5);
    }

    @Test
    void 유효_노출이_최소_노출_미만이면_점유율이_100퍼센트여도_집중으로_보지_않는다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of(stats(12L, 99, 0, 0)));
        when(adserving.memberConcentrations(DAY, 5)).thenReturn(List.of(
                new CampaignMemberConcentration(12L, 1, 99)));

        assertThat(step().collect(DAY).rows().getFirst().concentrationSuspected()).isFalse();
    }

    @Test
    void 점유율이_정확히_기준과_같으면_집중으로_보지_않고_넘으면_본다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of(stats(12L, 100, 0, 0), stats(13L, 100, 0, 0)));
        when(adserving.memberConcentrations(DAY, 5)).thenReturn(List.of(
                new CampaignMemberConcentration(12L, 9, 50),
                new CampaignMemberConcentration(13L, 9, 51)));

        List<FraudMetrics.Row> rows = step().collect(DAY).rows();

        assertThat(rows.get(0).concentrationSuspected()).isFalse();
        assertThat(rows.get(1).concentrationSuspected()).isTrue();
    }

    @Test
    void 유효_노출이_없고_무효_노출만_있는_캠페인도_행이_있고_회원_수는_0이다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of(stats(999L, 0, 120, 0)));
        when(adserving.memberConcentrations(DAY, 5)).thenReturn(List.of());

        assertThat(step().collect(DAY).rows())
                .containsExactly(new FraudMetrics.Row(999L, 0, 120, 0, 0, false));
    }

    @Test
    void 노출이_하나도_없는_캠페인은_행이_없다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of(stats(12L, 0, 0, 0)));
        when(adserving.memberConcentrations(DAY, 5)).thenReturn(List.of());

        assertThat(step().collect(DAY).rows()).isEmpty();
    }

    @Test
    void 모으기는_자기_표를_쓰지_않는다() {
        when(adserving.campaignStats(DAY)).thenReturn(List.of());
        when(adserving.memberConcentrations(DAY, 5)).thenReturn(List.of());

        step().collect(DAY);

        verifyNoInteractions(repository);
    }

    @Test
    void 공개는_그날_행을_교체하고_쓴_행_수를_돌려준다() {
        FraudMetrics collected = new FraudMetrics(List.of(
                new FraudMetrics.Row(12L, 4_000, 1_100, 4, 3_680, true),
                new FraudMetrics.Row(13L, 10, 0, 3, 5, false)));

        int written = step().publish(DAY, collected);

        assertThat(written).isEqualTo(2);
        verify(repository).replace(DAY, collected.rows(), LocalDateTime.of(2026, 10, 17, 1, 0));
    }
}
