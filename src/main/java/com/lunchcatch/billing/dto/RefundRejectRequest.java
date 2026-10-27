package com.lunchcatch.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 환불 반려 요청이며 사유는 점주가 목록에서 직접 보는 값이라 필수다
public record RefundRejectRequest(
        @NotBlank @Size(max = 255) String rejectReason
) {
}
