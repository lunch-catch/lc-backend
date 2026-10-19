package com.launchcatch.analytics.query.repository;

import java.time.LocalDate;

// 가게 지표 조회가 읽는 daily_store_analytics 한 행이다
public record StoreMetricsDailyRow(
        LocalDate businessDate,
        long newStoreCount,
        long cumulativeStoreCount,
        long activeCampaignCount
) {
}
