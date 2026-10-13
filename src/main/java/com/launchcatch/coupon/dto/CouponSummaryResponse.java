package com.launchcatch.coupon.dto;

public record CouponSummaryResponse(
        long receivedCouponCount,
        long usedCouponCount,
        long totalDiscountAmount
) {
}
