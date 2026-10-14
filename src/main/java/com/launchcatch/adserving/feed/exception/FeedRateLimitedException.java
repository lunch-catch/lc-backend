package com.launchcatch.adserving.feed.exception;

import lombok.Getter;

// 다시 요청할 수 있을 때까지 남은 초를 함께 든다. 그 값은 창을 들고 있는 쪽만 안다.
@Getter
public class FeedRateLimitedException extends FeedException {

    private final long retryAfterSeconds;

    public FeedRateLimitedException(long retryAfterSeconds) {
        super(FeedErrorCode.FEED_RATE_LIMITED);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
