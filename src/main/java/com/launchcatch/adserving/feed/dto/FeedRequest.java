package com.launchcatch.adserving.feed.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/*
 * GET /v1/feed 의 쿼리다. 둘 다 없으면 저장 위치를 쓰고 하나만 있으면 400 이다(feed-api-spec.md).
 * 둘 중 하나만 온 것은 요청 타입이 판정하므로 COMMON-002 로 간다(feed-api-design.md 의 오류 코드를 나눈 기준).
 */
public record FeedRequest(
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude
) {

    @AssertTrue
    public boolean isLocationPaired() {
        return (latitude == null) == (longitude == null);
    }
}
