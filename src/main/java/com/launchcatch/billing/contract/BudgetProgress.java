package com.launchcatch.billing.contract;

/*
 * 캠페인 하나의 그날 예산 소진 진행률이다.
 *
 * plannedAmountUntil 은 at 까지의 시간대별 목표를 누적한 값이다. 시간 중간이면 그 시간대 목표를 분 단위로
 * 비례해 나누고, 서빙 종료 뒤에는 그날 예약액 전체로 고정한다.
 * progressRate 는 todaySpend / plannedAmountUntil 이고 1.0 으로 자르지 않는다. 계획 합이 0 이면 0 이다.
 * 1.0 미만 우선, 1.2 초과 제외 같은 소비 기준과 노출 상한(예약액 / 단가 x 1.05)은 광고 서빙이 갖는다.
 */
public record BudgetProgress(
        Long campaignId,
        long reservedAmount,
        long unitPrice,
        long todaySpend,
        long plannedAmountUntil,
        double progressRate
) {
}
