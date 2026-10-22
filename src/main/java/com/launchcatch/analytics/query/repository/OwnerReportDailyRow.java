package com.launchcatch.analytics.query.repository;

import java.time.LocalDate;

public record OwnerReportDailyRow(
        LocalDate businessDate,
        long campaignId,
        long dailyBudget,
        long impressionUnitCost,
        long validImpressionCount,
        long invalidImpressionCount,
        long wishCount,
        long issuedCount,
        long redeemCount,
        long chargedAmount,
        long refundedAmount,
        long spentAmount
) {
}
