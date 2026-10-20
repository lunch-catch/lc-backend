package com.launchcatch.analytics.query.controller.dto;

import com.launchcatch.analytics.query.MemberUsageStats;

public record MemberUsageStatsResponse(long wishCount, long redeemCount, long savedAmount) {

    public static MemberUsageStatsResponse from(MemberUsageStats stats) {
        return new MemberUsageStatsResponse(stats.wishCount(), stats.redeemCount(), stats.savedAmount());
    }
}
