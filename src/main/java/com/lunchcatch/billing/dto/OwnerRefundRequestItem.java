package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.entity.RefundRequest;
import com.lunchcatch.billing.entity.RefundStatus;
import com.lunchcatch.global.config.ClockConfig;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

// 점주의 환불 요청 목록 한 건이며 반려 사유는 반려된 요청에만 있다
public record OwnerRefundRequestItem(
        Long refundRequestId,
        long amount,
        Long fee,
        RefundStatus status,
        String reason,
        String rejectReason,
        OffsetDateTime requestedAt,
        OffsetDateTime processedAt
) {

    public static OwnerRefundRequestItem from(RefundRequest request) {
        return new OwnerRefundRequestItem(request.getId(), request.getAmount(), request.getFee(),
                request.getStatus(), request.getReason(), request.getRejectReason(),
                toOffset(request.getCreatedAt()), toOffset(request.getProcessedAt()));
    }

    private static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
