package com.launchcatch.billing.contract;

// 캠페인 하나의 그 영업일에 적용된 하루 예산과 노출 단가다. 충전 직후 재예약이 늘린 값까지 반영한 마지막 값이다
public record CampaignDailyBudget(
        Long campaignId,
        long dailyBudget,
        long unitPrice
) {
}
