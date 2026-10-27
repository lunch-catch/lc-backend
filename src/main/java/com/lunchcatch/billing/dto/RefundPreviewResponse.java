package com.lunchcatch.billing.dto;

/*
 * 환불 요청 전에 보여 주는 예상 환불액이다. 아무것도 기록하지 않는다.
 * 수수료는 0 이라 예상 환불액은 환불 가능 잔액과 같다. 막히는 이유가 여럿이면 요청 API 의 검사 순서대로 첫 번째를 싣는다.
 */
public record RefundPreviewResponse(
        long refundablePoint,
        long fee,
        long expectedRefundAmount,
        boolean canRequest,
        RefundBlockedReason blockedReason
) {
}
