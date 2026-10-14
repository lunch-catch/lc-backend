package com.launchcatch.adserving.engine;

import java.util.Set;

// 사용자의 피드 필터다. 업종이 비어 있으면 전체 업종이다.
public record FeedFilter(Set<String> categories, FeedSort sort) {

    public static final FeedFilter DEFAULT = new FeedFilter(Set.of(), FeedSort.DISTANCE);

    public FeedFilter {
        categories = Set.copyOf(categories);
    }

    public boolean allows(String categoryCode) {
        return categories.isEmpty() || categories.contains(categoryCode);
    }
}
