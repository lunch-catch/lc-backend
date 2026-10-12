package com.launchcatch.billing.entity;

/*
 * 결제(충전) 한 건의 상태다. payment.status 의 CHECK 가 같은 다섯 값을 묶는다.
 *
 * V51 22행의 PENDING, SUCCESS, CANCELED 와 이름이 다르다. 저장 구조를 기준으로 삼은 것이고
 * SUCCESS 가 APPROVED 에 해당한다(billing.md 의 "V51 과 달라진 점").
 */
public enum PaymentStatus {

    /** 주문을 만들었고 아직 승인 결과를 받지 못했다. 결제창을 열기 전의 상태이기도 하다. */
    REQUESTED,

    APPROVED,

    FAILED,

    CANCELLED,

    /*
     * 승인 요청은 보냈으나 결과를 알 수 없다.
     * 실패로 단정하지 않는다. 실제로는 승인됐을 수 있어 재조회로 확정해야 한다.
     */
    UNKNOWN
}
