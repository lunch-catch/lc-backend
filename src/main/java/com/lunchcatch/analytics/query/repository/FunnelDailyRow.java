package com.lunchcatch.analytics.query.repository;

import java.time.LocalDate;

// 퍼널 조회가 읽는 daily_funnel_analytics 한 행이다
public record FunnelDailyRow(
        LocalDate businessDate,
        long validImpressionCount,
        long invalidImpressionCount,
        long wishCount,
        long issueCount,
        long redeemCount
) {
}
