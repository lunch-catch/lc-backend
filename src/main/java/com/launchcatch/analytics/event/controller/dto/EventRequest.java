package com.launchcatch.analytics.event.controller.dto;

import com.launchcatch.analytics.event.ClientEvent;
import java.time.OffsetDateTime;

public record EventRequest(
        String eventId,
        String eventType,
        OffsetDateTime occurredAt,
        Long campaignId,
        Long storeId,
        Long wishId
) {

    public ClientEvent toClientEvent() {
        return new ClientEvent(eventId, eventType, occurredAt, campaignId, storeId, wishId);
    }
}
