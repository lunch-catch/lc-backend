package com.launchcatch.coupon.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "coupon_usage_history")
@AttributeOverride(name = "id", column = @Column(name = "coupon_usage_history_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponUsageHistory extends BaseTimeEntity {

    @Column(name = "member_coupon_id", nullable = false, updatable = false)
    private Long memberCouponId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "discount_amount", nullable = false, updatable = false)
    private int discountAmount;

    private CouponUsageHistory(Long memberCouponId, Long storeId, int discountAmount) {
        if (Objects.requireNonNull(memberCouponId) <= 0 || Objects.requireNonNull(storeId) <= 0 || discountAmount < 0) {
            throw new IllegalArgumentException("쿠폰과 매장 ID는 양수이고 할인액은 0 이상이어야 한다.");
        }
        this.memberCouponId = memberCouponId;
        this.storeId = storeId;
        this.discountAmount = discountAmount;
    }

    // 서버가 확정한 할인액으로 사용 이력을 만든다.
    public static CouponUsageHistory record(Long memberCouponId, Long storeId, int discountAmount) {
        return new CouponUsageHistory(memberCouponId, storeId, discountAmount);
    }
}
