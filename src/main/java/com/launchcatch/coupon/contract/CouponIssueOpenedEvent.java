package com.launchcatch.coupon.contract;

import java.time.LocalDateTime;

public record CouponIssueOpenedEvent(String eventId, LocalDateTime occurredAt, long campaignId, long storeId,
        long couponEventId) {
}
