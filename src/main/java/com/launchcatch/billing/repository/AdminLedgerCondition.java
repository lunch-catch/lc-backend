package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.EntryType;
import java.time.LocalDateTime;

/*
 * 관리자 원장 목록의 질의 조건이다. type 과 campaignId 가 null 이면 걸지 않는다.
 * 기록 시각은 [fromInclusive, toExclusive) 범위이고 정렬은 기록 시각, ID 순으로 고정이다.
 */
public record AdminLedgerCondition(
        Long ownerId,
        EntryType type,
        LocalDateTime fromInclusive,
        LocalDateTime toExclusive,
        Long campaignId,
        boolean ascending,
        long offset,
        int limit
) {
}
