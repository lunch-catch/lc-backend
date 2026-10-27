package com.lunchcatch.billing.dto;

// 환불을 요청할 수 없는 이유이며 요청 API 가 검사하는 순서와 같다
public enum RefundBlockedReason {

    /** 환불할 잔액이 없다(BILLING-012). */
    NO_BALANCE,

    /** 집행 중인 캠페인이 있다. ACTIVE 뿐 아니라 SCHEDULED, PAUSED 도 포함한다(BILLING-010). */
    ACTIVE_CAMPAIGN,

    /** 처리를 기다리는 요청이 이미 있다(BILLING-011). */
    PENDING_REQUEST
}
