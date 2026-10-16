package com.launchcatch.billing.entity;

// 대사 종류이며 값은 chk_settlement_mismatch_check_type 과 같다
public enum MismatchCheckType {

    /** 결제대행사 승인 건과 원장 CHARGE 를 견준다. 대상 ID 는 결제 ID 다. */
    PG_VS_CHARGE,

    /** 당일 소진 카운터와 원장 DEDUCT 합을 견준다. 대상 ID 는 캠페인 ID 다. */
    CAMPAIGN_SPEND_VS_LEDGER,

    /*
     * 원장 DEDUCT 합이 그날 예약액을 넘었는지 본다. 대상 ID 는 캠페인 ID 이고, 기대값은 예약액, 실제값은 DEDUCT 합이다.
     * 해시가 원장으로 재구성되면 카운터와 원장이 같아져 CAMPAIGN_SPEND_VS_LEDGER 로는 보이지 않는 초과 소진을 잡는다.
     */
    CAMPAIGN_OVERSPEND;

    // 대상 ID 가 캠페인 ID 인 종류다. 아니면 결제 ID 다
    public boolean targetsCampaign() {
        return this != PG_VS_CHARGE;
    }
}
