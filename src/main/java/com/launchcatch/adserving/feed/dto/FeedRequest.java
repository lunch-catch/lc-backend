package com.launchcatch.adserving.feed.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/*
 * 피드 요청의 현재 위치다. 둘 다 없으면 회원의 저장 위치를 쓴다.
 * 하나만 오면 어느 위치인지 알 수 없어 400 으로 돌려보낸다.
 */
public record FeedRequest(
        @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @DecimalMin("-180") @DecimalMax("180") Double longitude
) {

    @AssertTrue
    public boolean isCoordinatePaired() {
        return (latitude == null) == (longitude == null);
    }

    public boolean hasCoordinate() {
        return latitude != null;
    }
}
