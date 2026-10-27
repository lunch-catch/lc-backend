package com.lunchcatch.billing.repository;

import com.lunchcatch.billing.entity.EntryType;
import java.time.LocalDateTime;
import java.util.Collection;

/*
 * 점주 원장 목록의 질의 조건이다. null 인 조건은 걸지 않는다.
 * types 는 비어 있지 않아야 하고 DEDUCT 를 담지 않는다. 호출하는 쪽이 그 보장을 진다.
 * 기록 시각은 [fromInclusive, toExclusive) 범위이고, 커서는 직전 페이지 마지막 행의 위치다.
 */
public record OwnerLedgerCondition(
        Long ownerId,
        Collection<EntryType> types,
        LocalDateTime fromInclusive,
        LocalDateTime toExclusive,
        Long campaignId,
        LocalDateTime cursorCreatedAt,
        Long cursorId,
        int limit
) {
}
