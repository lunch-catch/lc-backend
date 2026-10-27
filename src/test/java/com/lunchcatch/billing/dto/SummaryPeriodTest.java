package com.lunchcatch.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.global.exception.CommonErrorCode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SummaryPeriodTest {

    @Test
    void 일별은_날짜_하나가_구간_하나다() {
        List<PeriodBucket> buckets = SummaryPeriod.DAILY.buckets(
                new DateRange(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 1)));

        assertThat(buckets).extracting(PeriodBucket::label)
                .containsExactly("2026-09-29", "2026-09-30", "2026-10-01");
        assertThat(buckets.get(0).start()).isEqualTo(buckets.get(0).end());
    }

    @Test
    void 주별은_월요일을_라벨로_쓰고_걸친_주는_기간_안으로_자른다() {
        // 2026-09-30 은 수요일이고 그 주의 월요일은 09-28 이다
        List<PeriodBucket> buckets = SummaryPeriod.WEEKLY.buckets(
                new DateRange(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 12)));

        assertThat(buckets).extracting(PeriodBucket::label)
                .containsExactly("2026-09-28", "2026-10-05", "2026-10-12");
        assertThat(buckets.get(0).start()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(buckets.get(0).end()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(buckets.get(2).start()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(buckets.get(2).end()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    void 월별은_연월을_라벨로_쓰고_걸친_달은_기간_안으로_자른다() {
        List<PeriodBucket> buckets = SummaryPeriod.MONTHLY.buckets(
                new DateRange(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 11, 10)));

        assertThat(buckets).extracting(PeriodBucket::label)
                .containsExactly("2026-09", "2026-10", "2026-11");
        assertThat(buckets.get(0).start()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(buckets.get(0).end()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(buckets.get(1).start()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(buckets.get(1).end()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(buckets.get(2).end()).isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void 구간은_자기_날짜만_포함한다() {
        PeriodBucket bucket = new PeriodBucket("x", LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12));

        assertThat(bucket.contains(LocalDate.of(2026, 9, 9))).isFalse();
        assertThat(bucket.contains(LocalDate.of(2026, 9, 10))).isTrue();
        assertThat(bucket.contains(LocalDate.of(2026, 9, 12))).isTrue();
        assertThat(bucket.contains(LocalDate.of(2026, 9, 13))).isFalse();
    }

    @Test
    void 일별_92일은_허용하고_93일은_거절한다() {
        LocalDate from = LocalDate.of(2026, 1, 1);

        assertThat(SummaryPeriod.DAILY.buckets(new DateRange(from, from.plusDays(91)))).hasSize(92);
        assertThatThrownBy(() -> SummaryPeriod.DAILY.buckets(new DateRange(from, from.plusDays(92))))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void 주별_53주는_허용하고_54주는_거절한다() {
        LocalDate monday = LocalDate.of(2026, 1, 5);

        assertThat(SummaryPeriod.WEEKLY.buckets(new DateRange(monday, monday.plusWeeks(52)))).hasSize(53);
        assertThatThrownBy(() -> SummaryPeriod.WEEKLY.buckets(new DateRange(monday, monday.plusWeeks(53))))
                .isInstanceOf(BillingException.class);
    }

    @Test
    void 월별_13개월은_허용하고_14개월은_거절한다() {
        LocalDate first = LocalDate.of(2025, 1, 1);

        assertThat(SummaryPeriod.MONTHLY.buckets(new DateRange(first, first.plusMonths(12)))).hasSize(13);
        assertThatThrownBy(() -> SummaryPeriod.MONTHLY.buckets(new DateRange(first, first.plusMonths(13))))
                .isInstanceOf(BillingException.class);
    }
}
