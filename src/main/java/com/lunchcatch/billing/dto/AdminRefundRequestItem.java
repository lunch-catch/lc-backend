package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.entity.RefundStatus;
import java.time.OffsetDateTime;

/*
 * 관리자 환불 요청 목록의 한 건이다.
 * 처리 화면을 위해 점주의 현재 잔액과 집행 중 캠페인 여부를 함께 싣는다. 집행 중은 ACTIVE 뿐 아니라 SCHEDULED, PAUSED 도 포함한다.
 */
public record AdminRefundRequestItem(
        Long refundRequestId,
        Long ownerId,
        long amount,
        Long fee,
        RefundStatus status,
        String reason,
        String rejectReason,
        OffsetDateTime requestedAt,
        OffsetDateTime processedAt,
        Long processedBy,
        long currentBalance,
        boolean hasActiveCampaign
) {
}
