package com.launchcatch.billing.dto;

import jakarta.validation.constraints.Size;

// 환불 요청이며 금액은 받지 않고 항상 요청 시점 잔액 전액이다
public record RefundRequestCreateRequest(
        @Size(max = 255) String reason
) {
}
