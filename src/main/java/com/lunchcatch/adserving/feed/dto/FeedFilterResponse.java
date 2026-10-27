package com.lunchcatch.adserving.feed.dto;

import com.lunchcatch.adserving.engine.FeedSort;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record FeedFilterResponse(
        @Schema(description = "고른 업종 코드. 업종 코드 오름차순이다. 빈 배열이면 전체 업종", example = "[\"JAPANESE\", \"KOREAN\"]")
        List<String> categories,
        @Schema(description = "DISTANCE(거리순), DISCOUNT_RATE(할인율순)")
        FeedSort sort
) {
}
