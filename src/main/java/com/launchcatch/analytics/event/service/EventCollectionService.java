package com.launchcatch.analytics.event.service;

import com.launchcatch.analytics.event.ClientEvent;
import com.launchcatch.analytics.event.EventRecord;
import com.launchcatch.analytics.event.EventType;
import com.launchcatch.analytics.event.EventWriteResult;
import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EventCollectionService {

    private static final int MAX_EVENTS = 50;

    private final EventLogWriter writer;

    public EventCollectionService(EventLogWriter writer) {
        this.writer = writer;
    }

    public EventWriteResult collect(Long memberId, List<ClientEvent> events) {
        if (memberId == null || memberId <= 0 || events == null || events.size() > MAX_EVENTS) {
            throw invalidRequest();
        }

        List<EventRecord> records = events.stream()
                .map(event -> toRecord(memberId, event))
                .toList();
        return writer.write(records);
    }

    private EventRecord toRecord(Long memberId, ClientEvent event) {
        if (event == null || event.eventId() == null || event.eventType() == null || event.occurredAt() == null) {
            throw invalidRequest();
        }

        UUID eventId;
        try {
            eventId = UUID.fromString(event.eventId());
        } catch (IllegalArgumentException invalidId) {
            throw invalidRequest();
        }

        EventType eventType = clientEventType(event.eventType());
        return new EventRecord(
                eventId,
                eventType,
                event.occurredAt().toInstant(),
                memberId,
                event.campaignId(),
                event.storeId(),
                null,
                event.wishId(),
                null,
                null);
    }

    private EventType clientEventType(String value) {
        return switch (value) {
            case "poster_open" -> EventType.POSTER_OPEN;
            case "map_view" -> EventType.MAP_VIEW;
            case "wishlist_view" -> EventType.WISHLIST_VIEW;
            default -> throw new AnalyticsException(AnalyticsErrorCode.UNSUPPORTED_EVENT_TYPE);
        };
    }

    private AnalyticsException invalidRequest() {
        return new AnalyticsException(AnalyticsErrorCode.INVALID_EVENT_REQUEST);
    }
}
