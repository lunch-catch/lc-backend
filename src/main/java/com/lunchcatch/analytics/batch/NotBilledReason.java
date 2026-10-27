package com.lunchcatch.analytics.batch;

/*
 * 유효하지만 과금하지 않은 노출의 사유다.
 * daily_campaign_not_billed_analytics.not_billed_reason 에 같은 이름으로 남는다
 */
public enum NotBilledReason {
    NOT_BILLED_BUDGET,
    NOT_BILLED_PAUSED,
    NOT_BILLED_NO_PLAN,
    NOT_BILLED_CLOSED,
    NOT_BILLED_SOLD_OUT
}
