package com.lunchcatch.coupon.contract;

import java.time.LocalDateTime;

public record CouponExpiredEvent(String eventId, LocalDateTime occurredAt, long memberId, long campaignId, long storeId,
        long couponEventId, long memberCouponId) {
}
