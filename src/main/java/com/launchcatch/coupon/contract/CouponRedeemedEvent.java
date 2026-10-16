package com.launchcatch.coupon.contract;

import java.time.LocalDateTime;

public record CouponRedeemedEvent(String eventId, LocalDateTime occurredAt, long memberId, long campaignId, long storeId,
        long couponEventId, long memberCouponId) {
}
