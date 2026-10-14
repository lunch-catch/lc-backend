package com.launchcatch.adserving.feed.exception;

import lombok.Getter;

// 최근 60초 6회를 넘은 요청이다. 다시 요청할 수 있을 때까지 남은 초를 함께 든다
@Getter
public class FeedRateLimitedException extends FeedException {

    private final long retryAfterSeconds;

    public FeedRateLimitedException(long retryAfterSeconds) {
        super(FeedErrorCode.TOO_MANY_FEED_REQUESTS);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
