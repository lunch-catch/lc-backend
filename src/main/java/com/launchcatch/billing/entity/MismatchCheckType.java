package com.launchcatch.billing.entity;

// 대사 종류이며 값은 chk_settlement_mismatch_check_type 과 같다
public enum MismatchCheckType {

    /** 결제대행사 승인 건과 원장 CHARGE 를 견준다. 대상 ID 는 결제 ID 다. */
    PG_VS_CHARGE,

    /** 당일 소진 카운터와 원장 DEDUCT 합을 견준다. 대상 ID 는 캠페인 ID 다. */
    CAMPAIGN_SPEND_VS_LEDGER
}
