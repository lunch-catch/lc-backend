package com.launchcatch.adserving.feed.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record SwipeResponse(
        @Schema(description = "이번 요청이 반영됐으면 true. 같은 카드를 이미 넘긴 적이 있으면 false")
        boolean applied
) {
}
