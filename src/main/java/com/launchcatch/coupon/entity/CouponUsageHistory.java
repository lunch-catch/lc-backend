package com.launchcatch.coupon.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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
}
