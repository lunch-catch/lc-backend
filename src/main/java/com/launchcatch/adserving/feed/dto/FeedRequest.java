package com.launchcatch.adserving.feed.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

// 위치는 둘 다 보내거나 둘 다 보내지 않는다. 보내지 않으면 저장 위치를 쓴다.
public record FeedRequest(
        @Schema(description = "현재 위도다.", example = "37.4979", minimum = "-90", maximum = "90")
        @DecimalMin("-90") @DecimalMax("90")
        Double latitude,
        @Schema(description = "현재 경도다.", example = "127.0276", minimum = "-180", maximum = "180")
        @DecimalMin("-180") @DecimalMax("180")
        Double longitude
) {

    @Schema(hidden = true)
    @AssertTrue
    public boolean isLocationPaired() {
        return (latitude == null) == (longitude == null);
    }
}
