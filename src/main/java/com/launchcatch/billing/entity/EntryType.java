package com.launchcatch.billing.entity;

/*
 * 원장 한 줄의 종류다. point_ledger.entry_type 의 CHECK 가 같은 여섯 값을 묶는다.
 *
 * 부호는 종류에 종속된다. CHARGE 와 RELEASE 는 양수, RESERVE 와 DEDUCT 와 REFUND 는 음수,
 * ADJUST 는 0 이 아닌 값이다. chk_point_ledger_amount_sign 이 DB 에서 그것을 강제한다.
 */
public enum EntryType {

    /** 충전. 결제 승인이 확정될 때만 기록한다. */
    CHARGE,

    /** 예약. 그날 쓸 금액을 잔액에서 미리 뺀다. */
    RESERVE,

    /*
     * 노출 차감. 그날 예약액 안에서 실시간으로 빠진다.
     * 잔액 계산에서 제외한다. 예약 단계에서 이미 잔액에서 빠졌기 때문이다.
     */
    DEDUCT,

    /** 예약 해제. 그날 쓰지 않은 예약액을 잔액으로 되돌린다. */
    RELEASE,

    REFUND,

    /** 관리자의 수동 조정. 과오 차감 정정이 이 경로로만 들어온다. */
    ADJUST
}
