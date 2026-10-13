package com.launchcatch.coupon.dto;

import java.time.LocalDate;

public record CouponIssuanceLimitResponse(
        LocalDate date,
        int issuedCount,
        int dailyLimit,
        boolean dailyLimitReached
) {
}
