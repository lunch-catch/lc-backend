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

    @Column(name = "store_image_object_key", updatable = false, length = 255)
    private String storeImageObjectKey;

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
}
