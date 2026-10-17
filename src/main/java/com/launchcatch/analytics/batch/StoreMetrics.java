package com.launchcatch.analytics.batch;

/*
 * 하루 가게 지표다. 신규와 누적 입점은 가게 집계 조회가, 그날 말 집행 중 캠페인 수는 캠페인 집계 조회가
 * 준다.
 */
public record StoreMetrics(
        long newStoreCount,
        long cumulativeStoreCount,
        long activeCampaignCount
) {
}
