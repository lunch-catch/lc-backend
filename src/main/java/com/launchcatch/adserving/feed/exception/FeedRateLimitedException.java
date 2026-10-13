package com.launchcatch.adserving.feed.exception;

import lombok.Getter;

/*
 * 최근 60초 6회를 넘긴 피드 요청이다.
 * Retry-After 는 창을 들고 있는 쪽만 알아서 예외가 그 값을 싣고 간다.
 */
@Getter
public class FeedRateLimitedException extends FeedException {

    private final long retryAfterSeconds;

    public FeedRateLimitedException(long retryAfterSeconds) {
        super(FeedErrorCode.RATE_LIMITED);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
