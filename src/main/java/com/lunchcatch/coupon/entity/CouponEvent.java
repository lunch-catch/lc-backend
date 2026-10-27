package com.lunchcatch.coupon.entity;

import com.lunchcatch.coupon.CouponDiscountPrice;
import com.lunchcatch.coupon.exception.CouponException;
import com.lunchcatch.global.entity.BaseTimeEntity;
import com.lunchcatch.global.exception.CommonErrorCode;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
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

    @Column(name = "status_changed_at")
    private LocalDateTime statusChangedAt;

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

    private CouponEvent(long campaignId, LocalDate businessDate, int issueQuantity,
            LocalDateTime usableStartAt, LocalDateTime usableEndAt, Snapshot snapshot) {
        if (campaignId <= 0 || issueQuantity <= 0) {
            throw new IllegalArgumentException("캠페인 ID와 발급 수량은 양수여야 한다.");
        }
        this.businessDate = Objects.requireNonNull(businessDate);
        this.usableStartAt = Objects.requireNonNull(usableStartAt);
        this.usableEndAt = Objects.requireNonNull(usableEndAt);
        if (!usableEndAt.isAfter(usableStartAt)) {
            throw new IllegalArgumentException("사용 종료 시각은 시작 시각보다 늦어야 한다.");
        }
        Objects.requireNonNull(snapshot);
        if (snapshot.storeId() <= 0 || snapshot.storeName() == null || snapshot.storeName().isBlank()
                || snapshot.discountValue() <= 0 || snapshot.discountType() == null
                || snapshot.discountTargetType() == null
                || (snapshot.discountType() == DiscountType.PERCENT && snapshot.discountValue() > 100)) {
            throw new IllegalArgumentException("쿠폰 스냅샷이 유효하지 않다.");
        }
        boolean menu = snapshot.discountTargetType() == DiscountTargetType.MENU;
        if (menu && (snapshot.menuId() == null || snapshot.menuId() <= 0
                || snapshot.menuName() == null || snapshot.menuName().isBlank()
                || snapshot.menuPrice() == null || snapshot.menuPrice() < 0
                || snapshot.discountedPrice() == null || snapshot.discountedPrice() < 0
                || snapshot.discountedPrice() > snapshot.menuPrice())) {
            throw new IllegalArgumentException("메뉴 스냅샷이 유효하지 않다.");
        }
        if (!menu && (snapshot.discountType() != DiscountType.AMOUNT || snapshot.menuId() != null
                || snapshot.menuName() != null || snapshot.menuPrice() != null || snapshot.discountedPrice() != null)) {
            throw new IllegalArgumentException("전체 메뉴 할인에는 정액 할인만 허용한다.");
        }
        this.campaignId = campaignId;
        this.issueQuantity = issueQuantity;
        this.status = CouponEventStatus.SCHEDULED;
        this.storeId = snapshot.storeId();
        this.storeName = snapshot.storeName();
        this.storeImageUrl = snapshot.storeImageUrl();
        this.discountTargetType = snapshot.discountTargetType();
        this.discountType = snapshot.discountType();
        this.discountValue = snapshot.discountValue();
        this.targetMenuId = snapshot.menuId();
        this.targetMenuName = snapshot.menuName();
        this.targetMenuPrice = snapshot.menuPrice();
        this.targetMenuDiscountedPrice = snapshot.discountedPrice();
    }

    public static CouponEvent prepare(long campaignId, LocalDate businessDate, int issueQuantity,
            LocalDateTime usableStartAt, LocalDateTime usableEndAt, Snapshot snapshot) {
        return new CouponEvent(campaignId, businessDate, issueQuantity, usableStartAt, usableEndAt, snapshot);
    }

    // 생성 트랜잭션에서 잠근 캠페인의 현재 운영 상태를 새 회차에 반영한다.
    public void initializeCampaignState(boolean servable, LocalDateTime changedAt) {
        if (getId() != null || status != CouponEventStatus.SCHEDULED) {
            throw new IllegalStateException("새 회차에만 캠페인 초기 상태를 반영할 수 있다.");
        }
        status = servable ? CouponEventStatus.ACTIVE : CouponEventStatus.PAUSED;
        statusChangedAt = changedAt;
    }

    public record Snapshot(long storeId, String storeName, String storeImageUrl,
            DiscountTargetType discountTargetType, DiscountType discountType, int discountValue,
            Long menuId, String menuName, Long menuPrice, Long discountedPrice) { }

    // 특정 메뉴는 저장된 가격 차액을 사용하고 전체 메뉴 정액은 스냅샷 할인액을 10원 단위로 반올림한다.
    public int calculateDiscount() {
        if (discountTargetType == DiscountTargetType.MENU) {
            return Math.toIntExact(targetMenuPrice - targetMenuDiscountedPrice);
        }
        if (discountTargetType == DiscountTargetType.ALL && discountType == DiscountType.AMOUNT) {
            return Math.toIntExact(((long) discountValue + CouponDiscountPrice.ROUNDING_UNIT / 2)
                    / CouponDiscountPrice.ROUNDING_UNIT * CouponDiscountPrice.ROUNDING_UNIT);
        }
        throw new CouponException(CommonErrorCode.INTERNAL_ERROR);
    }
}
