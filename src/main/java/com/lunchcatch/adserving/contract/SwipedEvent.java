package com.lunchcatch.adserving.contract;

import java.time.LocalDateTime;
import java.util.UUID;

/*
 * 피드 카드를 넘긴 것이 처음 반영됐다. 분석이 퍼널의 wish 와 pass 로 받는다.
 * wishId 는 action 이 WISH 면 그 찜의 id 이고 PASS 면 null 이다.
 */
public record SwipedEvent(String eventId, LocalDateTime occurredAt, long memberId, long campaignId, long storeId,
        UUID serveId, SwipeAction action, Long wishId) {
}
