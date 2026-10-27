package com.lunchcatch.adserving.feed.dto;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

// 둘 다 없으면 저장 위치를 쓰고, 하나만 오면 400 이다.
public record FeedRequest(
        @Parameter(description = "현재 위도다. 경도와 함께 보낸다.", example = "37.4979")
        @DecimalMin("-90") @DecimalMax("90")
        Double latitude,
        @Parameter(description = "현재 경도다. 위도와 함께 보낸다.", example = "127.0276")
        @DecimalMin("-180") @DecimalMax("180")
        Double longitude
) {

    @Schema(hidden = true)
    @AssertTrue
    public boolean isLocationPaired() {
        return (latitude == null) == (longitude == null);
    }

    public boolean hasLocation() {
        return latitude != null && longitude != null;
    }
}
