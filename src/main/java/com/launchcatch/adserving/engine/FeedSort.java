package com.launchcatch.adserving.engine;

// 피드 필터의 정렬이다. 관련성 슬롯에만 적용한다(FR-067). feed_filter.sort_type 과 같은 값이다.
public enum FeedSort {
    DISTANCE,
    DISCOUNT_RATE
}
