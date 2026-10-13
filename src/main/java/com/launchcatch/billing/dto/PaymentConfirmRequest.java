package com.launchcatch.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 승인 확정 요청이다. 결제창의 성공 URL 이 돌려준 값을 프론트가 그대로 넘긴다.
 * 금액은 받지 않는다. 승인은 주문 때 저장한 금액으로만 요청한다.
 */
public record PaymentConfirmRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 100) String pgTid
) {
}
