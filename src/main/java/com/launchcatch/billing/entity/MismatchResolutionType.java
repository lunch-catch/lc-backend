package com.launchcatch.billing.entity;

// 대사 불일치를 닫은 방법이며 값은 chk_settlement_mismatch_resolution_type 과 같다
public enum MismatchResolutionType {

    /** 배치가 카운터를 원장 기준으로 다시 만들었다. 해결자는 NULL 이다. */
    CACHE_REBUILD,

    /** 배치가 빠진 원장 줄을 보정 기록했다. 해결자는 NULL 이다. */
    LEDGER_CORRECTION,

    /** 관리자가 양수나 음수 조정으로 금액을 바로잡았다. 해결자가 필수다(chk_settlement_mismatch_resolved_by). */
    MANUAL_ADJUSTMENT,

    /** 관리자가 원장을 바꾸지 않고 닫았다. 해결자가 필수다(chk_settlement_mismatch_resolved_by). */
    NO_ADJUSTMENT_REQUIRED
}
