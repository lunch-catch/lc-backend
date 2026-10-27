package com.lunchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.query.AggregationStatus;
import com.lunchcatch.analytics.query.AnalyticsPeriod;
import com.lunchcatch.analytics.query.SalesBucket;
import com.lunchcatch.analytics.query.SalesSeries;
import com.lunchcatch.analytics.query.TimeSeriesBucketState;
import com.lunchcatch.analytics.query.TimeSeriesState;
import com.lunchcatch.analytics.query.repository.SalesDailyRow;
import com.lunchcatch.analytics.query.repository.SalesQueryRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SalesQueryServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);
    private static final LocalDateTime AGGREGATED_AT = LocalDateTime.of(2026, 10, 12, 1, 5);

    @Mock
    private TimeSeriesStatusService statusService;
    @Mock
    private SalesQueryRepository repository;

    @InjectMocks
    private SalesQueryService service;

    private static SalesDailyRow row(LocalDate date, long charge, long deduct, long refund, long adjust,
            long positiveAdjust, long unspent) {
        return new SalesDailyRow(date, charge, deduct, refund, adjust, positiveAdjust, unspent);
    }

    private static TimeSeriesState state(TimeSeriesBucketState... buckets) {
        return new TimeSeriesState(AGGREGATED_AT, List.of(buckets));
    }

    @Test
    void 일별_구간은_그날_행의_값을_그대로_내려준다() {
        when(statusService.resolve(AnalyticsPeriod.DAILY, MONDAY, MONDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, MONDAY, AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, MONDAY)).thenReturn(List.of(
                row(MONDAY, 5_200_000, 4_100_000, 300_000, -1_000, 0, 2_750_000)));

        SalesSeries series = service.query(AnalyticsPeriod.DAILY, MONDAY, MONDAY);

        assertThat(series.aggregatedAt()).isEqualTo(AGGREGATED_AT);
        assertThat(series.items()).containsExactly(new SalesBucket("2026-10-05", AggregationStatus.FINAL,
                5_200_000L, 4_100_000L, 300_000L, -1_000L, 2_750_000L));
    }

    @Test
    void 주별_구간은_흐름은_합계이고_미소진_잔액은_마지막_날_값이다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, SUNDAY, AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                row(MONDAY, 1000, 300, 0, 0, 0, 900),
                row(MONDAY.plusDays(1), 2000, 400, 100, -50, 0, 800),
                row(SUNDAY, 0, 100, 0, 0, 0, 700)));

        SalesBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket.chargeAmount()).isEqualTo(3000);
        assertThat(bucket.spentAmount()).isEqualTo(800);
        assertThat(bucket.refundAmount()).isEqualTo(100);
        assertThat(bucket.adjustAmount()).isEqualTo(-50);
        assertThat(bucket.unspentBalance()).isEqualTo(700);
    }

    @Test
    void 소진액은_날마다_정정을_뺀_뒤_더하고_음수인_날도_0으로_자르지_않는다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, SUNDAY, AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                row(MONDAY, 0, 100, 0, 350, 350, 0),
                row(SUNDAY, 0, 1000, 0, 0, 0, 0)));

        SalesBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket.spentAmount()).isEqualTo(-250 + 1000);
    }

    @Test
    void 집계가_끝나지_않은_구간은_행이_있어도_금액을_null로_내린다() {
        when(statusService.resolve(AnalyticsPeriod.DAILY, MONDAY, MONDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, MONDAY, AggregationStatus.IN_PROGRESS)));
        when(repository.findBetween(MONDAY, MONDAY)).thenReturn(List.of(
                row(MONDAY, 1000, 300, 0, 0, 0, 900)));

        SalesBucket bucket = service.query(AnalyticsPeriod.DAILY, MONDAY, MONDAY).items().get(0);

        assertThat(bucket).isEqualTo(new SalesBucket("2026-10-05", AggregationStatus.IN_PROGRESS,
                null, null, null, null, null));
    }

    @Test
    void 구간_밖_날짜의_행은_더하지_않는다() {
        LocalDate nextMonday = SUNDAY.plusDays(1);
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, nextMonday)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, SUNDAY, AggregationStatus.FINAL),
                new TimeSeriesBucketState("2026-10-12", nextMonday, nextMonday, AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, nextMonday)).thenReturn(List.of(
                row(SUNDAY, 100, 0, 0, 0, 0, 10),
                row(nextMonday, 7, 0, 0, 0, 0, 20)));

        List<SalesBucket> items = service.query(AnalyticsPeriod.WEEKLY, MONDAY, nextMonday).items();

        assertThat(items.get(0).chargeAmount()).isEqualTo(100);
        assertThat(items.get(0).unspentBalance()).isEqualTo(10);
        assertThat(items.get(1).chargeAmount()).isEqualTo(7);
        assertThat(items.get(1).unspentBalance()).isEqualTo(20);
    }

    @Test
    void 집계_완료인데_구간_마지막_날_행이_없으면_미소진_잔액만_null이다() {
        when(statusService.resolve(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY)).thenReturn(state(
                new TimeSeriesBucketState("2026-10-05", MONDAY, SUNDAY, AggregationStatus.FINAL)));
        when(repository.findBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                row(MONDAY, 1000, 300, 0, 0, 0, 900)));

        SalesBucket bucket = service.query(AnalyticsPeriod.WEEKLY, MONDAY, SUNDAY).items().get(0);

        assertThat(bucket.chargeAmount()).isEqualTo(1000);
        assertThat(bucket.unspentBalance()).isNull();
    }

    @Test
    void 구간이_없으면_빈_목록이고_집계_시각은_상태_서비스_값이다() {
        when(statusService.resolve(AnalyticsPeriod.DAILY, MONDAY, MONDAY)).thenReturn(
                new TimeSeriesState(null, List.of()));
        when(repository.findBetween(MONDAY, MONDAY)).thenReturn(List.of());

        SalesSeries series = service.query(AnalyticsPeriod.DAILY, MONDAY, MONDAY);

        assertThat(series.items()).isEmpty();
        assertThat(series.aggregatedAt()).isNull();
    }
}
