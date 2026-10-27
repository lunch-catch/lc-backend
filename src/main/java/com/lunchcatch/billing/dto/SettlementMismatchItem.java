package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.entity.MismatchCheckType;
import com.lunchcatch.billing.entity.MismatchResolutionType;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/*
 * 관리자 대사 불일치 목록의 한 건이며 diff 는 actualValue - expectedValue 다.
 * ownerId 는 targetId 가 가리키는 결제나 캠페인의 점주이고 대상을 찾지 못하면 null 이다.
 */
public record SettlementMismatchItem(
        Long mismatchId,
        MismatchCheckType checkType,
        Long targetId,
        Long ownerId,
        LocalDate businessDate,
        long expectedValue,
        long actualValue,
        long diff,
        MismatchResolutionType resolutionType,
        OffsetDateTime createdAt,
        Long jobExecutionId,
        OffsetDateTime resolvedAt,
        Long resolvedBy
) {
}
