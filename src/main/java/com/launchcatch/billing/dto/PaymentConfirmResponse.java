package com.launchcatch.billing.dto;

import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PaymentStatus;

/*
 * 승인 확정 응답이다. 승인이면 200, 결과를 아직 모르면(UNKNOWN) 202 로 같은 모양을 내린다.
 * chargedPoint 는 컬럼이 아니라 금액에서 계산한 값이고(1원 = 1포인트) 승인됐을 때만 있다.
 */
public record PaymentConfirmResponse(
        Long paymentId,
        PaymentStatus status,
        DisplayStatus displayStatus,
        long amount,
        Long chargedPoint
) {

    public static PaymentConfirmResponse from(Payment payment) {
        Long chargedPoint = payment.getStatus() == PaymentStatus.APPROVED ? payment.getAmount() : null;
        return new PaymentConfirmResponse(payment.getId(), payment.getStatus(),
                DisplayStatus.of(payment.getStatus()), payment.getAmount(), chargedPoint);
    }
}
