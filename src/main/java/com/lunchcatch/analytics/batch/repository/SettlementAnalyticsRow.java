package com.lunchcatch.analytics.batch.repository;

// daily_settlement_analytics 한 행이다. 금액 열은 모두 표의 부호 그대로다(adjustAmount 만 ±)
public record SettlementAnalyticsRow(
        long chargeAmount,
        long deductAmount,
        long reservedAmount,
        long releasedAmount,
        long refundAmount,
        long adjustAmount,
        long positiveAdjustAmount,
        long unspentBalance
) {
}
