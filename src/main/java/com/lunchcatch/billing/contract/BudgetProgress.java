package com.lunchcatch.billing.contract;

/*
 * 캠페인 하나의 그날 예약액, 단가, 소진액이다.
 * 시간대별 목표와 진행률, 1.2 초과 제외 같은 소비 기준과 노출 상한(예약액 / 단가 x 1.05)은 광고 서빙이 갖는다(#828).
 */
public record BudgetProgress(
        Long campaignId,
        long reservedAmount,
        long unitPrice,
        long todaySpend
) {
}
