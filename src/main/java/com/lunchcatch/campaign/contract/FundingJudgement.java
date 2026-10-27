package com.lunchcatch.campaign.contract;

import java.time.LocalDate;

/**
 * 정산이 00:00 예약이나 충전 재예약에서 내린 예산 판정이다. 금액은 판정 근거 표시와 로그용이고
 * 캠페인은 보관하지 않는다.
 */
public record FundingJudgement(
        long campaignId,
        long ownerId,
        LocalDate businessDate,
        Judgement judgement,
        Cause cause,
        long reservedAmount,
        long unitPrice,
        long configuredDailyBudget,
        long todaySpend
) {
}
