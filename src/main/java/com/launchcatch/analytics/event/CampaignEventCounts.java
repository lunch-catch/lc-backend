package com.launchcatch.analytics.event;

public record CampaignEventCounts(
        long impressionCount,
        long wishCount,
        long passCount,
        long issuedCount,
        long redeemCount
) {
}
