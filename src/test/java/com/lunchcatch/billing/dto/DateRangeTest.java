package com.lunchcatch.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.global.exception.CommonErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DateRangeTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate D30 = LocalDate.of(2026, 9, 30);

    @Test
    void 양_끝을_포함한_일수를_센다() {
        assertThat(new DateRange(D1, D1).days()).isEqualTo(1);
        assertThat(new DateRange(D1, D30).days()).isEqualTo(30);
    }

    @Test
    void 날짜_조건은_시작일_자정부터_종료일_다음날_자정_직전까지의_범위가_된다() {
        DateRange range = new DateRange(D1, D30);

        assertThat(range.startInclusive()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(range.endExclusive()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
    }

    @Test
    void 기간의_모든_날짜를_오름차순으로_돌려준다() {
        assertThat(new DateRange(D1, LocalDate.of(2026, 9, 3)).dates())
                .containsExactly(D1, LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3));
    }

    @Test
    void 상한_일수까지는_허용하고_넘으면_거절한다() {
        assertThat(DateRange.of(D1, D30, 30).days()).isEqualTo(30);

        assertThatThrownBy(() -> DateRange.of(D1, D30, 29))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void 날짜가_없거나_뒤집힌_기간은_거절한다() {
        assertThatThrownBy(() -> new DateRange(null, D30)).isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> new DateRange(D1, null)).isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> new DateRange(D30, D1)).isInstanceOf(BillingException.class);
    }
}
