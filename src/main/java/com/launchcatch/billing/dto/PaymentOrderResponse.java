package com.launchcatch.billing.dto;

import com.launchcatch.billing.entity.Payment;

// 프론트는 이 주문 번호와 금액으로 결제창을 연다
public record PaymentOrderResponse(
        Long paymentId,
        String orderId,
        long amount
) {

    public static PaymentOrderResponse from(Payment payment) {
        return new PaymentOrderResponse(payment.getId(), payment.getOrderId(), payment.getAmount());
    }
}
