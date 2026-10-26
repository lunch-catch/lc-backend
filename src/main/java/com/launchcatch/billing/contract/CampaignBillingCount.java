package com.launchcatch.billing.contract;

// 한 영업일에 캠페인 하나가 받은 과금 판정의 상태별 건수다
public record CampaignBillingCount(
        Long campaignId,
        long billedCount,
        long notBilledBudgetCount,
        long notBilledPausedCount,
        long notBilledNoPlanCount,
        long notBilledClosedCount
) {

    // 마감 판정이 없던 호출자가 만든 집계는 마감 건수를 0 으로 둔다
    public CampaignBillingCount(Long campaignId, long billedCount, long notBilledBudgetCount,
                                long notBilledPausedCount, long notBilledNoPlanCount) {
        this(campaignId, billedCount, notBilledBudgetCount, notBilledPausedCount, notBilledNoPlanCount, 0);
    }
}
