package com.launchcatch.analytics.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FraudDateRangeTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    @Test
    void 시작일과_종료일이_같은_하루도_받는다() {
        assertThat(new FraudDateRange(FROM, FROM).to()).isEqualTo(FROM);
    }

    @Test
    void 기간이_정확히_31일이면_받는다() {
        assertThat(new FraudDateRange(FROM, FROM.plusDays(30)).to()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void 기간이_32일이면_받지_않는다() {
        assertThatThrownBy(() -> new FraudDateRange(FROM, FROM.plusDays(31)))
                .isInstanceOfSatisfying(AnalyticsException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.INVALID_PERIOD));
    }

    @Test
    void 시작일이_종료일보다_늦으면_받지_않는다() {
        assertThatThrownBy(() -> new FraudDateRange(FROM.plusDays(1), FROM))
                .isInstanceOf(AnalyticsException.class);
    }

    @Test
    void 날짜를_생략하면_받지_않는다() {
        assertThatThrownBy(() -> new FraudDateRange(null, FROM)).isInstanceOf(AnalyticsException.class);
        assertThatThrownBy(() -> new FraudDateRange(FROM, null)).isInstanceOf(AnalyticsException.class);
    }
}
