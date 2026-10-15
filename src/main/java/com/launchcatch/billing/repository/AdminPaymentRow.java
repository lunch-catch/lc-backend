package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.OutboxStatus;
import com.launchcatch.billing.entity.PaymentStatus;
import java.time.LocalDateTime;

/*
 * 관리자에게 보이는 결제 한 줄이다. 재조회 아웃박스 행이 없으면 outboxStatus 부터 lastError 까지 모두 null 이다.
 */
public record AdminPaymentRow(
        Long id,
        String orderId,
        String pgTid,
        Long ownerId,
        Long amount,
        PaymentStatus status,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime approvedAt,
        LocalDateTime canceledAt,
        OutboxStatus outboxStatus,
        Integer attemptCount,
        Integer maxRetry,
        LocalDateTime nextRetryAt,
        String lastError
) {
}
