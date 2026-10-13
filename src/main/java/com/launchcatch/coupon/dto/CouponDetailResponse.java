package com.launchcatch.coupon.dto;

import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponUsageHistory;
import com.launchcatch.coupon.entity.DiscountTargetType;
import com.launchcatch.coupon.entity.DiscountType;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.global.config.ClockConfig;
import java.time.OffsetDateTime;

public record CouponDetailResponse(
        Long memberCouponId,
        Long storeId,
        String storeName,
        String storeImageUrl,
        DiscountTargetType discountTargetType,
        DiscountType discountType,
        int discountValue,
        TargetMenu targetMenu,
        MemberCouponStatus state,
        boolean usable,
        OffsetDateTime issueTime,
        OffsetDateTime usableStartTime,
        OffsetDateTime usableEndTime,
        OffsetDateTime useTime,
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

    public record TargetMenu(Long menuId, String name, long price, long discountedPrice) {

        private static TargetMenu from(CouponEvent event) {
            if (event.getDiscountTargetType() != DiscountTargetType.MENU) {
                return null;
            }
            return new TargetMenu(event.getTargetMenuId(), event.getTargetMenuName(),
                    event.getTargetMenuPrice(), event.getTargetMenuDiscountedPrice());
        }
    }
}
