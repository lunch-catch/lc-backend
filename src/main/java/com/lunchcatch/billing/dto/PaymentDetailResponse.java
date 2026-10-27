package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PaymentStatus;
import com.lunchcatch.global.config.ClockConfig;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/*
 * 점주의 결제 한 건이다. 승인 확정이 202 를 돌려줬을 때 프론트가 이 응답으로 확정 여부를 폴링한다.
 * requestedAt 은 주문을 만든 시각(created_at)이고, 시각은 모두 +09:00 오프셋으로 내린다.
 */
public record PaymentDetailResponse(
        Long paymentId,
        String orderId,
        PaymentStatus status,
        DisplayStatus displayStatus,
        long amount,
        OffsetDateTime requestedAt,
        OffsetDateTime approvedAt
) {

    public static PaymentDetailResponse from(Payment payment) {
        return new PaymentDetailResponse(payment.getId(), payment.getOrderId(), payment.getStatus(),
                DisplayStatus.of(payment.getStatus()), payment.getAmount(),
                kst(payment.getCreatedAt()), kst(payment.getApprovedAt()));
    }

    static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
