package com.lunchcatch.coupon.dto;

import com.lunchcatch.coupon.entity.CouponEvent;
import com.lunchcatch.coupon.entity.CouponUsageHistory;
import com.lunchcatch.coupon.entity.DiscountTargetType;
import com.lunchcatch.coupon.entity.DiscountType;
import com.lunchcatch.coupon.entity.MemberCoupon;
import com.lunchcatch.coupon.entity.MemberCouponStatus;
import com.lunchcatch.global.config.ClockConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "쿠폰 정보와 사용 상태다.",
        requiredProperties = {"memberCouponId", "storeId", "storeName", "storeImageUrl", "discountTargetType", "discountType",
                "discountValue", "targetMenu", "state", "usable", "issueTime", "usableStartTime", "usableEndTime", "useTime", "discountAmount"})
public record CouponDetailResponse(
        @Schema(description = "조회·삭제·QR 생성에 사용하는 쿠폰 ID다.", example = "1001")
        Long memberCouponId,
        @Schema(description = "쿠폰을 사용할 매장 ID다.", example = "10")
        Long storeId,
        @Schema(description = "쿠폰을 사용할 가게명이다.", example = "런치식당")
        String storeName,
        @Schema(description = "대표 이미지 URL이며 이미지가 없으면 null이다.", format = "uri", nullable = true,
                example = "https://cdn.example.com/stores/10/logo-v1.jpg")
        String storeImageUrl,
        @Schema(description = "ALL은 전체 주문, MENU는 특정 메뉴 할인이다.", example = "MENU")
        DiscountTargetType discountTargetType,
        @Schema(description = "PERCENT는 정률, AMOUNT는 정액 할인이다.", example = "AMOUNT")
        DiscountType discountType,
        @Schema(description = "정률 할인은 %, 정액 할인은 원 단위다.", example = "3000")
        int discountValue,
        @Schema(description = "MENU의 할인 대상 메뉴이며 ALL이면 null이다.")
        TargetMenu targetMenu,
        @Schema(description = "쿠폰의 현재 상태다.",
                allowableValues = {"ISSUED", "USED", "EXPIRED"}, example = "ISSUED")
        MemberCouponStatus state,
        @Schema(description = "미사용이고 사용 가능 시간 안이면 true다. 회원 상태와 QR 유효성은 별도로 검증한다.", example = "true")
        boolean usable,
        @Schema(description = "쿠폰 발급 시각이다.", example = "2026-10-13T11:00:00+09:00")
        OffsetDateTime issueTime,
        @Schema(description = "사용 시작 시각이며 경계를 포함한다.", example = "2026-10-13T11:00:00+09:00")
        OffsetDateTime usableStartTime,
        @Schema(description = "사용 종료 시각이며 경계를 제외한다.", example = "2026-10-13T14:00:00+09:00")
        OffsetDateTime usableEndTime,
        @Schema(description = "사용 완료 시각이며 USED가 아니면 null이다.", nullable = true, example = "2026-10-13T12:00:30+09:00")
        OffsetDateTime useTime,
        @Schema(description = "확정 할인액(원)이며 USED가 아니면 null이다.", nullable = true, example = "3000")
        Integer discountAmount
) {

    /**
     * 쿠폰과 이벤트 스냅샷, 확정 사용 결과를 조회 응답으로 변환한다.
     * 쿠폰 상태와 사용 가능 여부는 서비스에서 계산한 값을 사용한다.
     *
     * @param coupon 조회한 회원 쿠폰
     * @param event 쿠폰에 연결된 이벤트 스냅샷
     * @param state 조회 시점의 쿠폰 상태
     * @param usable 조회 시점의 사용 가능 여부
     * @param history 사용 완료 쿠폰의 확정 이력이며, 그 외에는 null
     * @return 한국 시간 오프셋을 포함한 쿠폰 표시 정보와 사용 결과
     */
    public static CouponDetailResponse from(
            MemberCoupon coupon, CouponEvent event, MemberCouponStatus state, boolean usable, CouponUsageHistory history) {
        OffsetDateTime issueTime = coupon.getCreatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime();
        OffsetDateTime usableStartTime = coupon.getUsableStartAt().atZone(ClockConfig.ZONE).toOffsetDateTime();
        OffsetDateTime usableEndTime = coupon.getUsableEndAt().atZone(ClockConfig.ZONE).toOffsetDateTime();
        OffsetDateTime useTime = history == null ? null : history.getCreatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime();
        Integer discountAmount = history == null ? null : history.getDiscountAmount();

        return new CouponDetailResponse(
                coupon.getId(), event.getStoreId(), event.getStoreName(), event.getStoreImageUrl(),
                event.getDiscountTargetType(), event.getDiscountType(), event.getDiscountValue(), TargetMenu.from(event),
                state, usable, issueTime, usableStartTime, usableEndTime, useTime, discountAmount);
    }

    @Schema(name = "CouponTargetMenu", description = "할인 대상 메뉴 정보다.",
            types = {"object", "null"},
            requiredProperties = {"menuId", "name", "price", "discountedPrice"})
    public record TargetMenu(
            @Schema(description = "할인 대상 메뉴 ID다.", example = "701") Long menuId,
            @Schema(description = "메뉴명이다.", example = "숯불갈비 정식") String name,
            @Schema(description = "할인 전 가격(원)이다.", example = "12000", minimum = "0") long price,
            @Schema(description = "할인 후 가격(원)이다.", example = "9000", minimum = "0") long discountedPrice) {

        private static TargetMenu from(CouponEvent event) {
            if (event.getDiscountTargetType() != DiscountTargetType.MENU) {
                return null;
            }
            return new TargetMenu(event.getTargetMenuId(), event.getTargetMenuName(),
                    event.getTargetMenuPrice(), event.getTargetMenuDiscountedPrice());
        }
    }
}
