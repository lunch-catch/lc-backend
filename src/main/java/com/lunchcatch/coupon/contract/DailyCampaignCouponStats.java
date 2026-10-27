package com.lunchcatch.coupon.contract;

public record DailyCampaignCouponStats(long campaignId, long openCount, long issuedCount,
        long usedCount, long expiredCount) {
}
