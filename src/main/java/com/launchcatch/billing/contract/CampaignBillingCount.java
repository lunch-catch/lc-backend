package com.launchcatch.billing.contract;

// 한 영업일에 캠페인 하나가 받은 과금 판정의 상태별 건수다
public record CampaignBillingCount(
        Long campaignId,
        long billedCount,
        long notBilledBudgetCount,
        long notBilledPausedCount,
        long notBilledNoPlanCount
) {
}
