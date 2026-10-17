package com.launchcatch.analytics.event.entity;

import com.launchcatch.analytics.event.EventType;
import java.time.LocalDateTime;
import java.util.UUID;

public record EventLogEntry(
        UUID eventId,
        EventType eventType,
        Long memberId,
        Long campaignId,
        Long storeId,
        UUID serveId,
        Long wishId,
        Long couponEventId,
        Long memberCouponId,
        LocalDateTime occurredAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
