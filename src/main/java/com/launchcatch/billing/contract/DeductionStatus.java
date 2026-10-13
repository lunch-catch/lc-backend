package com.launchcatch.billing.contract;

// 노출 1건의 과금 판정이다. deduction_result.billing_status 에 같은 값으로 남는다
public enum DeductionStatus {

    // 과금했다. 원장에 DEDUCT 1행이 남는다
    BILLED,

    // 그날 예약액에 닿아 과금하지 않았다. 노출은 노출 상한까지 계속 받는다
    NOT_BILLED_BUDGET,

    // 캠페인이 중단된 뒤 도착해 과금하지 않았다
    NOT_BILLED_PAUSED
}
