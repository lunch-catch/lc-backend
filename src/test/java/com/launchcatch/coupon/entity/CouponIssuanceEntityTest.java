package com.launchcatch.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.coupon.exception.CouponException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CouponIssuanceEntityTest {

    @Test
    void 회원_쿠폰_발급은_양수_ID와_유효한_사용_시간을_요구한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 12, 0);
        UUID code = UUID.randomUUID();
        assertThatThrownBy(() -> MemberCoupon.issue(0L, 81L, code, now, now.plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberCoupon.issue(17L, null, code, now, now.plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberCoupon.issue(17L, 81L, code, now, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberCoupon.issue(17L, 81L, null, now, now.plusHours(1)))
                .isInstanceOf(NullPointerException.class);
        assertThat(MemberCoupon.issue(17L, 81L, code, now, now.plusHours(1)).getStatus())
                .isEqualTo(MemberCouponStatus.ISSUED);
    }

    @Test
    void 이미_발급된_재고를_다시_발급하지_않는다() {
        CouponInventory inventory = new CouponInventory();
        ReflectionTestUtils.setField(inventory, "status", CouponInventoryStatus.AVAILABLE);
        inventory.issue();
        assertThat(inventory.getStatus()).isEqualTo(CouponInventoryStatus.ISSUED);
        assertThatThrownBy(inventory::issue).isInstanceOf(CouponException.class);
    }
}
