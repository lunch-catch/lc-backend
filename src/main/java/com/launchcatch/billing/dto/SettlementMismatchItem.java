package com.launchcatch.billing.dto;

import com.launchcatch.billing.entity.MismatchCheckType;
import com.launchcatch.billing.entity.MismatchResolutionType;
import java.time.LocalDate;
import java.time.OffsetDateTime;

// 관리자 대사 불일치 목록의 한 건이며 diff 는 actualValue - expectedValue 다
public record SettlementMismatchItem(
        Long mismatchId,
        MismatchCheckType checkType,
        Long targetId,
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
