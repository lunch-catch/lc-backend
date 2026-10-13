package com.launchcatch.billing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 충전 주문 요청이며 금액이 정책에 맞는지는 서비스가 본다
public record PaymentOrderRequest(
        @NotNull @Positive Long amount
) {
}
