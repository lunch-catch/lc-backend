package com.launchcatch.adserving.feed.dto;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

// 피드 요청의 현재 위치다. 둘 다 없으면 저장 위치를 쓰고, 하나만 오면 400 이다
public record FeedLocationRequest(
        @Parameter(description = "현재 위도. -90 ~ 90", example = "37.4979")
        @DecimalMin("-90") @DecimalMax("90")
        Double latitude,
        @Parameter(description = "현재 경도. -180 ~ 180", example = "127.0276")
        @DecimalMin("-180") @DecimalMax("180")
        Double longitude
) {

    @Schema(hidden = true)
    @AssertTrue
    public boolean isBothOrNeither() {
        return (latitude == null) == (longitude == null);
    }

    public boolean isPresent() {
        return latitude != null;
    }
}
