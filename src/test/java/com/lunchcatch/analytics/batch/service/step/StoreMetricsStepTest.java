package com.lunchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.batch.StoreMetrics;
import com.lunchcatch.analytics.batch.repository.StoreAnalyticsRepository;
import com.lunchcatch.campaign.contract.CampaignStatsQueryService;
import com.lunchcatch.store.contract.FinalizedStoreCounts;
import com.lunchcatch.store.contract.StoreStatsQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreMetricsStepTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T16:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private StoreStatsQueryService stores;
    @Mock
    private CampaignStatsQueryService campaigns;
    @Mock
    private StoreAnalyticsRepository repository;

    private StoreMetricsStep step() {
        return new StoreMetricsStep(stores, campaigns, repository, CLOCK);
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step().name()).isEqualTo("StoreMetricsStep");
    }

    @Test
    void 가게_집계_조회의_신규_누적_입점과_캠페인_집계_조회의_집행_중_캠페인_수를_합친다() {
        when(stores.finalizedStoreCounts(DAY)).thenReturn(new FinalizedStoreCounts(6, 142));
        when(campaigns.activeCampaignCount(DAY)).thenReturn(97L);

        StoreMetrics metrics = step().collect(DAY);

        assertThat(metrics).isEqualTo(new StoreMetrics(6, 142, 97));
    }

    @Test
    void 모으기는_자기_표를_쓰지_않는다() {
        when(stores.finalizedStoreCounts(DAY)).thenReturn(new FinalizedStoreCounts(0, 0));
        when(campaigns.activeCampaignCount(DAY)).thenReturn(0L);

        step().collect(DAY);

        verifyNoInteractions(repository);
    }

    @Test
    void 공개는_그날_행을_교체하고_쓴_행_수_1을_돌려준다() {
        StoreMetrics collected = new StoreMetrics(6, 142, 97);

        int written = step().publish(DAY, collected);

        assertThat(written).isEqualTo(1);
        verify(repository).replace(DAY, collected, LocalDateTime.of(2026, 10, 17, 1, 0));
    }
}
