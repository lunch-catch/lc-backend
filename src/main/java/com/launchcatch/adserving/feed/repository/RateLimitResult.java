package com.launchcatch.adserving.feed.repository;

import java.time.Instant;

// count 는 이번 요청이 통과했으면 그것까지 센 수다. oldest 는 창 안에서 가장 오래된 요청 시각이다.
public record RateLimitResult(boolean allowed, int count, Instant oldest) {
}
