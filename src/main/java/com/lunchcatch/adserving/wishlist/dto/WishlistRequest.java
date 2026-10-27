package com.lunchcatch.adserving.wishlist.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// 요청한 사용자는 본문이 아니라 로그인 정보로 정한다.
public record WishlistRequest(
        @Schema(description = "찜할 캠페인", example = "101")
        @NotNull
        Long campaignId
) {
}
