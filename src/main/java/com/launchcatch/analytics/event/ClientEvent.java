package com.launchcatch.analytics.event;

import java.time.OffsetDateTime;

public record ClientEvent(
        String eventId,
        String eventType,
        OffsetDateTime occurredAt,
        Long campaignId,
        Long storeId,
        Long wishId
) {
}
