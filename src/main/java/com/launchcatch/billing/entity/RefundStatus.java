package com.launchcatch.billing.entity;

/*
 * 환불 요청의 상태다. 값은 chk_refund_request_status 와 같다.
 * 환불이 mock 이라 승인과 완료 사이에 외부 처리가 없다. 그래서 승인하면 REQUESTED 에서 COMPLETED 로 바로 간다.
 */
public enum RefundStatus {

    /** 관리자 처리를 기다린다. 점주마다 하나뿐이다(uk_refund_request_pending). */
    REQUESTED,

    /** 스키마에 있지만 쓰지 않는다. 실제 환불을 연동해 승인과 완료가 갈리면 쓴다. */
    APPROVED,

    /** 반려했다. 원장은 바뀌지 않는다. */
    REJECTED,

    /** 승인해 원장에 REFUND 를 기록했다. */
    COMPLETED
}
