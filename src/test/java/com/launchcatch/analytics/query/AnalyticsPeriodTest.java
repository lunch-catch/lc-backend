package com.launchcatch.analytics.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyticsPeriodTest {

    @Test
    void 일별은_날짜를_하나씩_묶는다() {
        LocalDate from = LocalDate.of(2026, 9, 29);

        List<TimeSeriesBucket> buckets = AnalyticsPeriod.DAILY.buckets(
                new AnalyticsDateRange(from, from.plusDays(2)));

        assertThat(buckets).extracting(TimeSeriesBucket::label)
                .containsExactly("2026-09-29", "2026-09-30", "2026-10-01");
    }

    @Test
    void 주별은_월요일을_라벨로_쓰고_요청_기간으로_자른다() {
        List<TimeSeriesBucket> buckets = AnalyticsPeriod.WEEKLY.buckets(
                new AnalyticsDateRange(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 12)));

        assertThat(buckets).extracting(TimeSeriesBucket::label)
                .containsExactly("2026-09-28", "2026-10-05", "2026-10-12");
        assertThat(buckets.getFirst().start()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(buckets.getFirst().end()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(buckets.getLast().start()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(buckets.getLast().end()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    void 월별은_연월을_라벨로_쓰고_요청_기간으로_자른다() {
        List<TimeSeriesBucket> buckets = AnalyticsPeriod.MONTHLY.buckets(
                new AnalyticsDateRange(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 11, 10)));

        assertThat(buckets).extracting(TimeSeriesBucket::label)
                .containsExactly("2026-09", "2026-10", "2026-11");
        assertThat(buckets.getFirst().start()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(buckets.getFirst().end()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(buckets.getLast().end()).isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void 일별_92일은_허용하고_93일은_거절한다() {
        LocalDate from = LocalDate.of(2026, 1, 1);

        assertThat(AnalyticsPeriod.DAILY.buckets(
                new AnalyticsDateRange(from, from.plusDays(91)))).hasSize(92);
        assertInvalid(() -> AnalyticsPeriod.DAILY.buckets(
                new AnalyticsDateRange(from, from.plusDays(92))));
    }

    @Test
    void 주별_53주는_허용하고_54주는_거절한다() {
        LocalDate monday = LocalDate.of(2026, 1, 5);

        assertThat(AnalyticsPeriod.WEEKLY.buckets(
                new AnalyticsDateRange(monday, monday.plusWeeks(52)))).hasSize(53);
        assertInvalid(() -> AnalyticsPeriod.WEEKLY.buckets(
                new AnalyticsDateRange(monday, monday.plusWeeks(53))));
    }

    @Test
    void 월별_13개월은_허용하고_14개월은_거절한다() {
        LocalDate first = LocalDate.of(2025, 1, 1);

        assertThat(AnalyticsPeriod.MONTHLY.buckets(
                new AnalyticsDateRange(first, first.plusMonths(12)))).hasSize(13);
        assertInvalid(() -> AnalyticsPeriod.MONTHLY.buckets(
                new AnalyticsDateRange(first, first.plusMonths(13))));
    }

    private void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(AnalyticsException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AnalyticsErrorCode.INVALID_PERIOD));
    }
}
