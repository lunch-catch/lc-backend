package com.launchcatch.adserving.engine;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/*
 * 피드 한 번을 배정하는 데 드는 입력이다. service 가 저장소와 다른 도메인에서 모아 넘긴다.
 * categories 가 비어 있으면 전체 업종이다. excludedCampaignIds 는 그날 찜과 패스, 하루 2회와 세션 1회에 걸린 캠페인이다.
 */
public record FeedInput(
        Viewer viewer,
        LocalDate businessDate,
        List<FeedCandidate> candidates,
        Set<String> categories,
        FeedSort sort,
        Set<Long> excludedCampaignIds,
        CategoryPreference preference,
        BudgetSnapshot budget
) {

    public FeedInput {
        candidates = List.copyOf(candidates);
        categories = Set.copyOf(categories);
        excludedCampaignIds = Set.copyOf(excludedCampaignIds);
    }
}
