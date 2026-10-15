package com.launchcatch.billing.dto;

import com.launchcatch.billing.entity.OutboxStatus;
import com.launchcatch.billing.entity.PaymentStatus;
import java.time.OffsetDateTime;

/*
 * 관리자에게 내리는 결제 한 줄이다. 확인 중(UNKNOWN)도 점주 화면과 달리 그대로 보여 준다.
 * reconciliation 은 재조회 아웃박스 행이 있을 때만 채운다.
 * 결제대행사 취소 식별자는 채우는 경로가 없어 내리지 않는다.
 */
public record AdminPaymentItem(
        Long paymentId,
        String orderId,
        String pgTid,
        Long ownerId,
        long amount,
        long chargedPoint,
        PaymentStatus status,
        String failureReason,
        OffsetDateTime requestedAt,
        OffsetDateTime approvedAt,
        OffsetDateTime canceledAt,
        Reconciliation reconciliation
) {

    public record Reconciliation(
            OutboxStatus status,
            int attemptCount,
            int maxRetry,
            OffsetDateTime nextRetryAt,
            String lastError
    ) {
    }
}
