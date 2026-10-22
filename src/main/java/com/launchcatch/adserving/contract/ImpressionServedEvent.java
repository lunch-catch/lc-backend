package com.launchcatch.adserving.contract;

import java.time.LocalDateTime;
import java.util.UUID;

// 유효 노출이 처음 저장됐다. 분석이 퍼널의 impression 으로 받는다.
public record ImpressionServedEvent(String eventId, LocalDateTime occurredAt, long memberId, long campaignId,
        long storeId, UUID serveId) {
}
