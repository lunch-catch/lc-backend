package com.lunchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.query.AggregationStatus;
import com.lunchcatch.analytics.query.AnalyticsPeriod;
import com.lunchcatch.analytics.query.StoreMetricsBucket;
import com.lunchcatch.analytics.query.StoreMetricsSeries;
import com.lunchcatch.analytics.query.TimeSeriesBucketState;
import com.lunchcatch.analytics.query.TimeSeriesState;
import com.lunchcatch.analytics.query.repository.StoreMetricsDailyRow;
import com.lunchcatch.analytics.query.repository.StoreMetricsQueryRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreMetricsQueryServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);
    private static final LocalDateTime AGGREGATED_AT = LocalDateTime.of(2026, 10, 12, 1, 5);

    @Mock
    private TimeSeriesStatusService statusService;
    @Mock
    private StoreMetricsQueryRepository repository;

    @InjectMocks
    private StoreMetricsQueryService service;

    private static StoreMetricsDailyRow row(LocalDate date, long added, long cumulative, long activeCampaigns) {
        return new StoreMetricsDailyRow(date, added, cumulative, activeCampaigns);
    }

    private static TimeSeriesState state(TimeSeriesBucketState... buckets) {
        return new TimeSeriesState(AGGREGATED_AT, List.of(buckets));
    }

    private static TimeSeriesBucketState week(AggregationStatus status) {
        return new TimeSeriesBucketState("2026-10-05", MONDAY, SUNDAY, status);
    }

    @Test
    void 일별_구간은_그날_행의_값을_그대로_내려준다() {
        when(statusService.resolve(AnalyticsPeriod.DAILY, MONDAY, MONDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, MONDAY, AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, MONDAY)).thenReturn(List.of(row(MONDAY, 6, 142, 97)));

        StoreMetricsSeries series = service.query(AnalyticsPeriod.DAILY, MONDAY, MONDAY);

        assertThat(series.aggregatedAt()).isEqualTo(AGGREGATED_AT);
        assertThat(series.items()).containsExactly(
                new StoreMetricsBucket("2026-10-05", AggregationStatus.FINAL, 6L, 142L, 97L));
    }

    @Test
    void 주별_구간은_신규는_합계이고_누적과_집행_중_캠페인은_마지막_날_값이다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(week(AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                row(MONDAY, 1, 100, 50),
                row(MONDAY.plusDays(1), 2, 102, 60),
                row(SUNDAY, 3, 105, 55)));

        StoreMetricsBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket.newStoreCount()).isEqualTo(6);
        assertThat(bucket.cumulativeStoreCount()).isEqualTo(105);
        assertThat(bucket.activeCampaignCount()).isEqualTo(55);
    }

    @Test
    void 구간_밖_행은_합산하지_않는다() {
        when(statusService.resolve(AnalyticsPeriod.DAILY, MONDAY, MONDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, MONDAY, AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, MONDAY)).thenReturn(List.of(
                row(MONDAY.minusDays(1), 99, 99, 99), row(MONDAY, 1, 2, 3)));

        StoreMetricsBucket bucket = service.query(AnalyticsPeriod.DAILY, MONDAY, MONDAY).items().get(0);

        assertThat(bucket).isEqualTo(new StoreMetricsBucket("2026-10-05", AggregationStatus.FINAL, 1L, 2L, 3L));
    }

    @Test
    void 마지막_날_행이_없으면_스냅샷_지표는_null이다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(week(AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of(row(MONDAY, 4, 100, 50)));

        StoreMetricsBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket.cumulativeStoreCount()).isNull();
        assertThat(bucket.activeCampaignCount()).isNull();
        assertThat(bucket.newStoreCount()).isEqualTo(4);
    }

    @Test
    void 집계_전_구간은_수치가_모두_null이다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(week(AggregationStatus.IN_PROGRESS)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of());

        StoreMetricsBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket).isEqualTo(new StoreMetricsBucket("2026-10-05", AggregationStatus.IN_PROGRESS,
                null, null, null));
    }
}
