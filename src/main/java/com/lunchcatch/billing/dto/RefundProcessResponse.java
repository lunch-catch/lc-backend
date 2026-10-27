package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.entity.RefundRequest;
import com.lunchcatch.billing.entity.RefundStatus;
import com.lunchcatch.global.config.ClockConfig;
import java.time.OffsetDateTime;

// 승인하거나 반려한 환불 요청이며 수수료가 0 이라 지급액은 amount 와 같다
public record RefundProcessResponse(
        Long refundRequestId,
        RefundStatus status,
        long amount,
        Long fee,
        OffsetDateTime processedAt
) {

    public static RefundProcessResponse from(RefundRequest request) {
        return new RefundProcessResponse(request.getId(), request.getStatus(), request.getAmount(),
                request.getFee(), request.getProcessedAt().atZone(ClockConfig.ZONE).toOffsetDateTime());
    }
}
