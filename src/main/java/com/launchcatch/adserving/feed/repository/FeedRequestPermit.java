package com.launchcatch.adserving.feed.repository;

import java.time.Instant;

/*
 * 피드 요청 제한의 판정이다.
 * 허용이면 nextAvailableAt 은 다음 요청이 거부되지 않는 가장 이른 시각이고, 거절이면 다시 요청할 수 있는 시각이다.
 * requestId 는 창에 넣은 이번 요청의 값이다. 200 으로 끝나지 않으면 이 값으로 창에서 뺀다.
 */
public record FeedRequestPermit(boolean allowed, Instant nextAvailableAt, String requestId) {
}
