package com.launchcatch.coupon.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "coupon_event")
@AttributeOverride(name = "id", column = @Column(name = "coupon_event_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponEvent extends BaseTimeEntity {

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "issue_quantity", nullable = false)
    private int issueQuantity;

    @Column(name = "usable_start_at", nullable = false, updatable = false)
    private LocalDateTime usableStartAt;

    @Column(name = "usable_end_at", nullable = false, updatable = false)
    private LocalDateTime usableEndAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponEventStatus status;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "store_name", nullable = false, updatable = false, length = 100)
    private String storeName;

    @Column(name = "store_image_url", updatable = false, length = 512)
    private String storeImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_target_type", nullable = false, updatable = false, length = 20)
    private DiscountTargetType discountTargetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, updatable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, updatable = false)
    private int discountValue;

    @Column(name = "target_menu_id", updatable = false)
    private Long targetMenuId;

    @Column(name = "target_menu_name", updatable = false, length = 100)
    private String targetMenuName;

    @Column(name = "target_menu_price", updatable = false)
    private Long targetMenuPrice;

    @Column(name = "target_menu_discounted_price", updatable = false)
    private Long targetMenuDiscountedPrice;

    // 메뉴는 저장된 가격 차액을 사용하고 전체 주문은 정률·정액을 계산하여 주문 금액으로 제한한다.
    public int calculateDiscount(int orderAmount) {
        long amount;
        if (discountTargetType == DiscountTargetType.MENU) {
            amount = targetMenuPrice - targetMenuDiscountedPrice;
        } else if (discountType == DiscountType.PERCENT) {
            // 전체 주문 정률은 소수 원을 유지하여 10원 단위로 반올림하며 100%는 전액 할인한다.
            amount = discountValue == 100 ? orderAmount : ((long) orderAmount * discountValue + 500) / 1000 * 10;
        } else if (discountValue >= orderAmount) {
            // 주문 금액 이상이면 반올림 전에 전액 할인한다.
            amount = orderAmount;
        } else {
            // 전체 주문 정액 할인액을 10원 단위로 반올림한다.
            amount = ((long) discountValue + 5) / 10 * 10;
        }
        return Math.toIntExact(Math.min(amount, orderAmount));
    }
}
