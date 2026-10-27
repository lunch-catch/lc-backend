package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PaymentStatus;
import java.time.OffsetDateTime;

/*
 * 점주 결제 내역의 한 줄이다.
 * 확인 중(UNKNOWN)인 결제는 원장에 아무것도 남기지 않아 포인트 변동 내역에 나타나지 않으므로, 결제 단위로 따로 보여 준다.
 */
public record PaymentHistoryItem(
        Long paymentId,
        long amount,
        PaymentStatus status,
        DisplayStatus displayStatus,
        String failureReason,
        OffsetDateTime requestedAt,
        OffsetDateTime approvedAt,
        OffsetDateTime canceledAt
) {

    public static PaymentHistoryItem from(Payment payment) {
        return new PaymentHistoryItem(payment.getId(), payment.getAmount(), payment.getStatus(),
                DisplayStatus.of(payment.getStatus()), payment.getFailureReason(),
                PaymentDetailResponse.kst(payment.getCreatedAt()), PaymentDetailResponse.kst(payment.getApprovedAt()),
                PaymentDetailResponse.kst(payment.getCanceledAt()));
    }
}
