package com.lunchcatch.analytics.query;

/*
 * A-10 의 type 필터다.
 * 생략하면 둘 중 하나라도 걸린 캠페인을 모두 내려준다
 */
public enum CampaignWarningType {
    INVALID_RATIO,
    CONCENTRATION
}
