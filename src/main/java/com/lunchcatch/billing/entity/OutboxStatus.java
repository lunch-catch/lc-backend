package com.lunchcatch.billing.entity;

// 결제 재조회 아웃박스의 상태이며 payment_reconciliation_outbox.status 의 CHECK 가 같은 세 값을 묶는다
public enum OutboxStatus {

    /** 재조회를 기다린다. next_retry_at 이 필수다. */
    PENDING,

    /** 결제 결과가 확정됐다. */
    RESOLVED,

    /** 재조회 상한을 넘었다. 관리자 재조회로 사람이 확정한다. */
    FAILED_PERMANENT
}
