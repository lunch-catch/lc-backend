package com.launchcatch.analytics.query.repository;

import java.time.LocalDate;

// 매출 조회가 읽는 daily_settlement_analytics 한 행이다
public record SalesDailyRow(
        LocalDate businessDate,
        long chargeAmount,
        long deductAmount,
        long refundAmount,
        long adjustAmount,
        long positiveAdjustAmount,
        long unspentBalance
) {
}
