package com.launchcatch.adserving.feed.repository;

import java.util.Map;
import java.util.Set;

// 사용자에게 그날 캠페인마다 내려준 횟수와 지금 세션에 내려준 캠페인이다
public record ServedHistory(Map<Long, Integer> todayCounts, Set<Long> sessionCampaignIds) {

    public static final ServedHistory EMPTY = new ServedHistory(Map.of(), Set.of());

    public ServedHistory {
        todayCounts = Map.copyOf(todayCounts);
        sessionCampaignIds = Set.copyOf(sessionCampaignIds);
    }
}
