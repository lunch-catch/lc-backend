package com.launchcatch.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "누적 쿠폰 통계다.", requiredProperties = {"receivedCouponCount", "usedCouponCount", "totalDiscountAmount"})
public record CouponSummaryResponse(
        @Schema(description = "삭제한 쿠폰을 포함한 누적 발급 수다.", example = "12", minimum = "0")
        long receivedCouponCount,
        @Schema(description = "누적 사용 수다.", example = "8", minimum = "0")
        long usedCouponCount,
        @Schema(description = "누적 할인액이며 원 단위다.", example = "24000", minimum = "0")
        long totalDiscountAmount
) {
}
