package com.launchcatch.billing.dto;

// 소진 집계를 묶는 기준이다
public enum SpendGroupBy {

    /** 날짜별 합계다. 캠페인은 합쳐지므로 campaignId 는 내리지 않는다. */
    DAY,

    /** 캠페인별 합계다. 기간 전체를 합치므로 date 는 내리지 않는다. */
    CAMPAIGN,

    /** 날짜와 캠페인 조합별이다. */
    DAY_CAMPAIGN
}
