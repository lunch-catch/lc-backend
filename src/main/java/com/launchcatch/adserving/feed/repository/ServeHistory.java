package com.launchcatch.adserving.feed.repository;

import java.util.Map;
import java.util.Set;

// 한 사용자가 오늘 캠페인마다 받은 횟수와 지금 세션에서 받은 캠페인이다.
public record ServeHistory(Map<Long, Integer> todayCounts, Set<Long> sessionCampaignIds) {

    public static final ServeHistory EMPTY = new ServeHistory(Map.of(), Set.of());

    public ServeHistory {
        todayCounts = Map.copyOf(todayCounts);
        sessionCampaignIds = Set.copyOf(sessionCampaignIds);
    }
}
