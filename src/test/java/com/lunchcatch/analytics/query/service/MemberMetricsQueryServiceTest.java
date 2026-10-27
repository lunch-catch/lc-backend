package com.lunchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.query.AggregationStatus;
import com.lunchcatch.analytics.query.AnalyticsPeriod;
import com.lunchcatch.analytics.query.MemberMetricsBucket;
import com.lunchcatch.analytics.query.MemberMetricsSeries;
import com.lunchcatch.analytics.query.TimeSeriesBucketState;
import com.lunchcatch.analytics.query.TimeSeriesState;
import com.lunchcatch.analytics.query.repository.MemberMetricsDailyRow;
import com.lunchcatch.analytics.query.repository.MemberMetricsQueryRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberMetricsQueryServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);
    private static final LocalDateTime AGGREGATED_AT = LocalDateTime.of(2026, 10, 12, 1, 5);

    @Mock
    private TimeSeriesStatusService statusService;
    @Mock
    private MemberMetricsQueryRepository repository;

    @InjectMocks
    private MemberMetricsQueryService service;

    private static MemberMetricsDailyRow row(LocalDate date, long added, long cumulative, long active,
            long withdrawn, long suspended) {
        return new MemberMetricsDailyRow(date, added, cumulative, active, withdrawn, suspended);
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
        when(repository.findBetween(MONDAY, MONDAY)).thenReturn(List.of(row(MONDAY, 120, 5430, 910, 48, 3)));

        MemberMetricsSeries series = service.query(AnalyticsPeriod.DAILY, MONDAY, MONDAY);

        assertThat(series.aggregatedAt()).isEqualTo(AGGREGATED_AT);
        assertThat(series.items()).containsExactly(new MemberMetricsBucket("2026-10-05", AggregationStatus.FINAL,
                120L, 5430L, 910L, 48L, 3L));
    }

    @Test
    void 주별_구간은_신규는_합계이고_누적과_탈퇴와_정지는_마지막_날_값이다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(week(AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                row(MONDAY, 10, 100, 50, 1, 0),
                row(MONDAY.plusDays(1), 20, 120, 60, 2, 1),
                row(SUNDAY, 5, 125, 70, 4, 2)));

        MemberMetricsBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket.newMemberCount()).isEqualTo(35);
        assertThat(bucket.cumulativeMemberCount()).isEqualTo(125);
        assertThat(bucket.withdrawnCount()).isEqualTo(4);
        assertThat(bucket.suspendedCount()).isEqualTo(2);
    }

    @Test
    void 일평균_활성_회원은_묶음의_일수로_나눈다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(week(AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                row(MONDAY, 0, 0, 100, 0, 0), row(MONDAY.plusDays(1), 0, 0, 100, 0, 0),
                row(MONDAY.plusDays(2), 0, 0, 100, 0, 0), row(MONDAY.plusDays(3), 0, 0, 100, 0, 0),
                row(MONDAY.plusDays(4), 0, 0, 100, 0, 0), row(MONDAY.plusDays(5), 0, 0, 100, 0, 0),
                row(SUNDAY, 0, 0, 70, 0, 0)));

        MemberMetricsBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket.avgDailyActiveMembers()).isEqualTo(96);
    }

    @Test
    void 일평균은_소수점_아래를_반올림한다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, MONDAY.plusDays(1))).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, MONDAY.plusDays(1), AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, MONDAY.plusDays(1))).thenReturn(List.of(
                row(MONDAY, 0, 0, 10, 0, 0), row(MONDAY.plusDays(1), 0, 0, 5, 0, 0)));

        MemberMetricsBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, MONDAY.plusDays(1)).items().get(0);

        assertThat(bucket.avgDailyActiveMembers()).isEqualTo(8);
    }

    @Test
    void 일부만_걸친_구간은_요청_범위로_잘린_일수로_나눈다() {
        LocalDate wednesday = MONDAY.plusDays(2);
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, wednesday, SUNDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", wednesday, SUNDAY, AggregationStatus.FINAL)));
        when(repository.findBetween(wednesday, SUNDAY)).thenReturn(List.of(
                row(wednesday, 0, 0, 100, 0, 0), row(wednesday.plusDays(1), 0, 0, 100, 0, 0),
                row(wednesday.plusDays(2), 0, 0, 100, 0, 0), row(wednesday.plusDays(3), 0, 0, 100, 0, 0),
                row(SUNDAY, 0, 0, 100, 0, 0)));

        MemberMetricsBucket bucket = service.query(AnalyticsPeriod.WEEKLY, wednesday, SUNDAY).items().get(0);

        assertThat(bucket.avgDailyActiveMembers()).isEqualTo(100);
    }

    @Test
    void 구간_밖_행은_합산하지_않는다() {
        when(statusService.resolve(AnalyticsPeriod.DAILY, MONDAY, MONDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, MONDAY, AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, MONDAY)).thenReturn(List.of(
                row(MONDAY.minusDays(1), 99, 99, 99, 99, 99), row(MONDAY, 1, 2, 3, 4, 5)));

        MemberMetricsBucket bucket = service.query(AnalyticsPeriod.DAILY, MONDAY, MONDAY).items().get(0);

        assertThat(bucket).isEqualTo(new MemberMetricsBucket("2026-10-05", AggregationStatus.FINAL, 1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void 마지막_날_행이_없으면_스냅샷_지표는_null이다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(week(AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of(row(MONDAY, 10, 100, 50, 1, 0)));

        MemberMetricsBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket.cumulativeMemberCount()).isNull();
        assertThat(bucket.withdrawnCount()).isNull();
        assertThat(bucket.suspendedCount()).isNull();
        assertThat(bucket.newMemberCount()).isEqualTo(10);
    }

    @Test
    void 집계_전_구간은_수치가_모두_null이다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(week(AggregationStatus.IN_PROGRESS)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of());

        MemberMetricsBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket).isEqualTo(new MemberMetricsBucket("2026-10-05", AggregationStatus.IN_PROGRESS,
                null, null, null, null, null));
    }
}
