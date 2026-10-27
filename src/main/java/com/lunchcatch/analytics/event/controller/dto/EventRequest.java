package com.lunchcatch.analytics.event.controller.dto;

import com.lunchcatch.analytics.event.ClientEvent;
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
