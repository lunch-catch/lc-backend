package com.lunchcatch.billing.reconciliation.repository;

import com.lunchcatch.billing.reconciliation.entity.MismatchCheckType;
import com.lunchcatch.billing.reconciliation.entity.MismatchResolutionType;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 대사 불일치 한 건을 엔티티 없이 읽은 값이다
public record SettlementMismatchRow(
        Long id,
        MismatchCheckType checkType,
        Long targetId,
        LocalDate businessDate,
        long expectedValue,
        long actualValue,
        MismatchResolutionType resolutionType,
        Long jobExecutionId,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt,
        Long resolvedBy
) {
}
