package com.lunchcatch.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.coupon.repository.CouponBatchIssueRepository.NewCoupon;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class CouponBatchIssueRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);

    @Test
    void 서로_다른_배열에_같은_코드가_있으면_같은_쿠폰_값이다() {
        NewCoupon first = new NewCoupon(1, 2, new byte[]{1, 2, 3}, NOW, NOW.plusHours(2), NOW);
        NewCoupon second = new NewCoupon(1, 2, new byte[]{1, 2, 3}, NOW, NOW.plusHours(2), NOW);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(new HashSet<>(List.of(first, second))).hasSize(1);
    }

    @Test
    void 코드의_내용이_다르면_다른_쿠폰_값이다() {
        NewCoupon first = new NewCoupon(1, 2, new byte[]{1, 2, 3}, NOW, NOW.plusHours(2), NOW);
        NewCoupon second = new NewCoupon(1, 2, new byte[]{1, 2, 4}, NOW, NOW.plusHours(2), NOW);

        assertThat(first).isNotEqualTo(second);
        assertThat(new HashSet<>(List.of(first, second))).hasSize(2);
    }

    @Test
    void 쿠폰_값의_문자열에_코드의_내용을_표시한다() {
        NewCoupon coupon = new NewCoupon(1, 2, new byte[]{1, 2, 3}, NOW, NOW.plusHours(2), NOW);

        assertThat(coupon).hasToString("NewCoupon[eventId=1, memberId=2, couponCode=[1, 2, 3], usableStartAt="
                + NOW + ", usableEndAt=" + NOW.plusHours(2) + ", issuedAt=" + NOW + "]");
    }
}
