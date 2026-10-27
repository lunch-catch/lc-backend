package com.lunchcatch.adserving.feed.exception;

import lombok.Getter;

// 최근 60초 6회를 넘은 요청이다. 다시 요청할 수 있을 때까지 남은 초를 Retry-After 헤더로 싣는다.
@Getter
public class FeedRateLimitedException extends FeedException {

    private final long retryAfterSeconds;

    public FeedRateLimitedException(long retryAfterSeconds) {
        super(FeedErrorCode.RATE_LIMITED);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
