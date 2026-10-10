package com.launchcatch.billing.entity;

/*
 * 원장 한 줄을 누가 기록했는지다.
 * point_ledger.source 의 CHECK 가 같은 세 값을 묶는다.
 */
public enum LedgerSource {

    /** 요청 처리 중에 기록했다. 결제 승인과 노출 차감이 그렇다. */
    REALTIME,

    /** 배치가 기록했다. 예약과 해제, 대사 보정이 그렇다. */
    RECONCILIATION,

    /** 사람이 기록했다. ADJUST 는 이 값만 허용한다(chk_point_ledger_adjust_source). */
    MANUAL
}
