package com.lunchcatch.analytics.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lunchcatch.analytics.exception.AnalyticsErrorCode;
import com.lunchcatch.analytics.exception.AnalyticsException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnalyticsDateRangeTest {

    private static final LocalDate SEPTEMBER_FIRST = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEPTEMBER_THIRTIETH = LocalDate.of(2026, 9, 30);

    @Test
    void 양_끝을_포함한_날짜를_오름차순으로_돌려준다() {
        AnalyticsDateRange range = new AnalyticsDateRange(
                SEPTEMBER_FIRST, SEPTEMBER_FIRST.plusDays(2));

        assertThat(range.dates()).containsExactly(
                SEPTEMBER_FIRST, SEPTEMBER_FIRST.plusDays(1), SEPTEMBER_FIRST.plusDays(2));
    }

    @Test
    void 날짜가_없거나_뒤집힌_기간은_거절한다() {
        assertInvalid(() -> new AnalyticsDateRange(null, SEPTEMBER_THIRTIETH));
        assertInvalid(() -> new AnalyticsDateRange(SEPTEMBER_FIRST, null));
        assertInvalid(() -> new AnalyticsDateRange(SEPTEMBER_THIRTIETH, SEPTEMBER_FIRST));
    }

    private void assertInvalid(Runnable constructor) {
        assertThatThrownBy(constructor::run)
                .isInstanceOfSatisfying(AnalyticsException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AnalyticsErrorCode.INVALID_PERIOD));
    }
}
