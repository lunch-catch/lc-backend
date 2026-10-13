package com.launchcatch.adserving.feed.dto;

/*
 * 피드 응답의 상태다.
 * SERVING 이 아니면 카드가 없다
 */
public enum FeedStatus {

    SERVING,
    OUT_OF_SERVING_HOURS,
    NO_CANDIDATE
}
