package com.launchcatch.analytics.event.service;

import com.launchcatch.analytics.event.EventRecord;
import com.launchcatch.analytics.event.EventType;
import com.launchcatch.analytics.event.EventWriteResult;
import com.launchcatch.analytics.event.entity.EventLogEntry;
import com.launchcatch.analytics.event.repository.EventLogRepository;
import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventLogWriter {

    private final EventLogRepository repository;
    private final Clock clock;

    public EventLogWriter(EventLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public EventWriteResult write(EventRecord record) {
        return writeValidated(List.of(record));
    }

    @Transactional
    public EventWriteResult write(List<EventRecord> records) {
        if (records == null) {
            throw invalidRequest();
        }
        return writeValidated(records);
    }

    private EventWriteResult writeValidated(List<EventRecord> records) {
        records.forEach(this::validate);
        Instant receivedAt = clock.instant();
        int accepted = 0;
        int duplicated = 0;

        for (EventRecord record : records) {
            EventLogEntry entry = toEntry(record, receivedAt);
            if (repository.insert(entry)) {
                accepted++;
            } else {
                duplicated++;
            }
        }
        return new EventWriteResult(accepted, duplicated);
    }

    private void validate(EventRecord record) {
        if (record == null
                || record.eventId() == null
                || record.eventType() == null
                || record.occurredAt() == null) {
            throw invalidRequest();
        }

        validatePositive(record.memberId());
        validatePositive(record.campaignId());
        validatePositive(record.storeId());
        validatePositive(record.wishId());
        validatePositive(record.couponEventId());
        validatePositive(record.memberCouponId());
        validateMember(record);
        validateTarget(record);
        validateWish(record);
        validateCoupon(record);
    }

    private void validateMember(EventRecord record) {
        boolean valid = record.eventType() == EventType.ISSUE_OPEN
                ? record.memberId() == null
                : record.memberId() != null;
        if (!valid) {
            throw invalidRequest();
        }
    }

    private void validateTarget(EventRecord record) {
        boolean targetless = record.eventType() == EventType.ISSUE_OPEN
                || record.eventType() == EventType.MAP_VIEW
                || record.eventType() == EventType.WISHLIST_VIEW;
        boolean valid = targetless
                ? record.campaignId() == null && record.storeId() == null
                : record.campaignId() != null && record.storeId() != null;
        if (!valid) {
            throw invalidRequest();
        }
    }

    private void validateWish(EventRecord record) {
        boolean valid = switch (record.eventType()) {
            case WISH -> record.wishId() != null;
            case WISHLIST_VIEW -> true;
            default -> record.wishId() == null;
        };
        if (!valid) {
            throw invalidRequest();
        }
    }

    private void validateCoupon(EventRecord record) {
        boolean valid = switch (record.eventType()) {
            case ISSUE_OPEN -> record.couponEventId() != null && record.memberCouponId() == null;
            case ISSUE -> record.couponEventId() != null && record.memberCouponId() != null;
            case REDEEM, EXPIRE -> record.memberCouponId() != null;
            default -> record.couponEventId() == null && record.memberCouponId() == null;
        };
        if (!valid) {
            throw invalidRequest();
        }
    }

    private void validatePositive(Long value) {
        if (value != null && value <= 0) {
            throw invalidRequest();
        }
    }

    private EventLogEntry toEntry(EventRecord record, Instant receivedAt) {
        Instant occurredAt = record.occurredAt().isAfter(receivedAt)
                ? receivedAt
                : record.occurredAt();
        LocalDateTime occurredAtKst = LocalDateTime.ofInstant(occurredAt, ClockConfig.ZONE);
        LocalDateTime receivedAtKst = LocalDateTime.ofInstant(receivedAt, ClockConfig.ZONE);
        return new EventLogEntry(
                record.eventId(),
                record.eventType(),
                record.memberId(),
                record.campaignId(),
                record.storeId(),
                record.serveId(),
                record.wishId(),
                record.couponEventId(),
                record.memberCouponId(),
                occurredAtKst,
                receivedAtKst,
                receivedAtKst);
    }

    private AnalyticsException invalidRequest() {
        return new AnalyticsException(AnalyticsErrorCode.INVALID_EVENT_REQUEST);
    }
}
