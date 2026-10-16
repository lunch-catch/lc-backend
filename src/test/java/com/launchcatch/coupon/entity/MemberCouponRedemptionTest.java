package com.launchcatch.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MemberCouponRedemptionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);
    private static final UUID TOKEN = UUID.fromString("6c75d5bc-cae3-4eeb-a573-fc3bcb1673a5");

    @Test
    void 사용은_USED로_전이하고_마지막_QR_토큰과_버전을_보존한다() {
        MemberCoupon coupon = coupon();
        coupon.redeem(TOKEN, 1, NOW);
        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.USED);
        assertThat(coupon.getQrToken()).isEqualTo(TOKEN);
        assertThat(coupon.getQrVersion()).isEqualTo(1);
        assertThat(coupon.getQrExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThatThrownBy(() -> coupon.redeem(TOKEN, 1, NOW.plusDays(1)))
                .isInstanceOfSatisfying(CouponException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_ALREADY_USED));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 60})
    void QR_생성_전과_만료_시각부터는_사용할_수_없다(long seconds) {
        MemberCoupon coupon = coupon();
        assertError(coupon, TOKEN, 1, NOW.plusSeconds(seconds), CouponErrorCode.QR_NOT_VALID);
        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.ISSUED);
    }

    @Test
    void 잠금_전에_조회한_토큰과_버전이_교체됐으면_거절한다() {
        MemberCoupon coupon = coupon();
        coupon.renewQr(UUID.randomUUID(), NOW.plusSeconds(1));
        assertError(coupon, TOKEN, 1, NOW.plusSeconds(2), CouponErrorCode.QR_NOT_VALID);
    }

    @Test
    void 새_QR로_사용한_쿠폰도_이전_QR에는_이미_사용된_오류를_반환하지_않는다() {
        MemberCoupon coupon = coupon();
        UUID renewed = UUID.randomUUID();
        coupon.renewQr(renewed, NOW.plusSeconds(1));
        coupon.redeem(renewed, 2, NOW.plusSeconds(2));
        assertError(coupon, TOKEN, 1, NOW.plusSeconds(3), CouponErrorCode.QR_NOT_VALID);
    }

    @Test
    void 삭제한_쿠폰과_사용_기간이_끝난_쿠폰은_사용하지_않는다() {
        MemberCoupon removed = coupon();
        removed.remove();
        assertError(removed, TOKEN, 1, NOW, CouponErrorCode.COUPON_NOT_AVAILABLE);
        assertError(coupon(), TOKEN, 1, NOW.plusHours(1), CouponErrorCode.COUPON_NOT_AVAILABLE);
    }

    private MemberCoupon coupon() {
        MemberCoupon coupon = MemberCoupon.issue(17L, 21L, UUID.randomUUID(), NOW.minusHours(1), NOW.plusHours(1));
        coupon.renewQr(TOKEN, NOW);
        return coupon;
    }

    private void assertError(MemberCoupon coupon, UUID token, int version, LocalDateTime now, CouponErrorCode expected) {
        assertThatThrownBy(() -> coupon.redeem(token, version, now)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
