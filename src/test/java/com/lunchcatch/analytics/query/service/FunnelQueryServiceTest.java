package com.lunchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.query.AggregationStatus;
import com.lunchcatch.analytics.query.AnalyticsPeriod;
import com.lunchcatch.analytics.query.FunnelBucket;
import com.lunchcatch.analytics.query.FunnelSeries;
import com.lunchcatch.analytics.query.TimeSeriesBucketState;
import com.lunchcatch.analytics.query.TimeSeriesState;
import com.lunchcatch.analytics.query.repository.FunnelDailyRow;
import com.lunchcatch.analytics.query.repository.FunnelQueryRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FunnelQueryServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);
    private static final LocalDateTime AGGREGATED_AT = LocalDateTime.of(2026, 10, 12, 1, 5);

    @Mock
    private TimeSeriesStatusService statusService;
    @Mock
    private FunnelQueryRepository repository;

    @InjectMocks
    private FunnelQueryService service;

    private static FunnelDailyRow row(LocalDate date, long valid, long invalid, long wish, long issue, long redeem) {
        return new FunnelDailyRow(date, valid, invalid, wish, issue, redeem);
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
        when(repository.findBetween(MONDAY, MONDAY)).thenReturn(List.of(row(MONDAY, 182_000, 1_450, 9_100, 2_300, 700)));

        FunnelSeries series = service.query(AnalyticsPeriod.DAILY, MONDAY, MONDAY);

        FunnelBucket bucket = series.items().get(0);
        assertThat(series.aggregatedAt()).isEqualTo(AGGREGATED_AT);
        assertThat(bucket.validImpressionCount()).isEqualTo(182_000);
        assertThat(bucket.invalidImpressionCount()).isEqualTo(1_450);
        assertThat(bucket.wishCount()).isEqualTo(9_100);
        assertThat(bucket.issueCount()).isEqualTo(2_300);
        assertThat(bucket.redeemCount()).isEqualTo(700);
        assertThat(bucket.validRatio()).isCloseTo(182_000.0 / 183_450.0, within(1e-12));
    }

    @Test
    void 주별_구간은_건수를_합하고_비율은_합산한_값으로_다시_계산한다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(week(AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                row(MONDAY, 900, 100, 10, 5, 2),
                row(MONDAY.plusDays(1), 100, 0, 20, 6, 3),
                row(SUNDAY, 0, 100, 30, 7, 4)));

        FunnelBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket.validImpressionCount()).isEqualTo(1000);
        assertThat(bucket.invalidImpressionCount()).isEqualTo(200);
        assertThat(bucket.wishCount()).isEqualTo(60);
        assertThat(bucket.issueCount()).isEqualTo(18);
        assertThat(bucket.redeemCount()).isEqualTo(9);
        assertThat(bucket.validRatio()).isCloseTo(1000.0 / 1200.0, within(1e-12));
    }

    @Test
    void 비율은_일별_비율의_평균이_아니라_합산한_분자와_분모로_계산한다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(week(AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                row(MONDAY, 1, 0, 0, 0, 0),
                row(SUNDAY, 0, 9, 0, 0, 0)));

        FunnelBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket.validRatio()).isCloseTo(0.1, within(1e-12));
    }

    @Test
    void 노출이_하나도_없으면_건수는_0이고_비율은_null이다() {
        when(statusService.resolve(AnalyticsPeriod.DAILY, MONDAY, MONDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, MONDAY, AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, MONDAY)).thenReturn(List.of(row(MONDAY, 0, 0, 0, 0, 0)));

        FunnelBucket bucket = service.query(AnalyticsPeriod.DAILY, MONDAY, MONDAY).items().get(0);

        assertThat(bucket.validImpressionCount()).isZero();
        assertThat(bucket.validRatio()).isNull();
    }

    @Test
    void 구간_밖_행은_합산하지_않는다() {
        when(statusService.resolve(AnalyticsPeriod.DAILY, MONDAY, MONDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, MONDAY, AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, MONDAY)).thenReturn(List.of(
                row(MONDAY.minusDays(1), 99, 99, 99, 99, 99), row(MONDAY, 10, 0, 1, 2, 3)));

        FunnelBucket bucket = service.query(AnalyticsPeriod.DAILY, MONDAY, MONDAY).items().get(0);

        assertThat(bucket.validImpressionCount()).isEqualTo(10);
        assertThat(bucket.wishCount()).isEqualTo(1);
    }

    @Test
    void 집계_전_구간은_수치가_모두_null이다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(week(AggregationStatus.IN_PROGRESS)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of());

        FunnelBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket).isEqualTo(new FunnelBucket("2026-10-05", AggregationStatus.IN_PROGRESS,
                null, null, null, null, null, null));
    }
}
