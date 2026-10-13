package com.launchcatch.adserving.feed.repository;

import java.time.Instant;

/*
 * 피드 요청 제한의 판정이다.
 * 허용이면 nextAvailableAt 은 다음 요청이 거부되지 않는 가장 이른 시각이고, 거절이면 다시 요청할 수 있는 시각이다.
 */
public record FeedRequestPermit(boolean allowed, Instant nextAvailableAt) {
}
