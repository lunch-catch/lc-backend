package com.lunchcatch.analytics.event;

import java.time.Instant;
import java.util.UUID;

public record EventRecord(
        UUID eventId,
        EventType eventType,
        Instant occurredAt,
        Long memberId,
        Long campaignId,
        Long storeId,
        UUID serveId,
        Long wishId,
        Long couponEventId,
        Long memberCouponId
) {
}
