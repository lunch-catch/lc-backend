package com.launchcatch.billing.dto;

import com.launchcatch.billing.entity.RefundRequest;
import com.launchcatch.billing.entity.RefundStatus;

// 만든 환불 요청이며 원장에는 아직 아무것도 기록되지 않았다
public record RefundRequestCreateResponse(
        Long refundRequestId,
        long amount,
        Long fee,
        RefundStatus status
) {

    public static RefundRequestCreateResponse from(RefundRequest request) {
        return new RefundRequestCreateResponse(request.getId(), request.getAmount(), request.getFee(),
                request.getStatus());
    }
}
