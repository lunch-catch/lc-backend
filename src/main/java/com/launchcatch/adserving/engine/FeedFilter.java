package com.launchcatch.adserving.engine;

import java.util.Set;

/*
 * 회원의 피드 필터다. 업종은 10장 전부에, 정렬은 관련성 7장에만 건다(70행).
 * categoryCodes 가 비어 있으면 전체 업종이다.
 */
public record FeedFilter(FeedSort sort, Set<String> categoryCodes) {

    // feed_filter 행이 없는 회원의 값이다
    public static final FeedFilter DEFAULT = new FeedFilter(FeedSort.DISTANCE, Set.of());

    public FeedFilter {
        categoryCodes = Set.copyOf(categoryCodes);
    }

    public boolean allows(String categoryCode) {
        return categoryCodes.isEmpty() || categoryCodes.contains(categoryCode);
    }
}
