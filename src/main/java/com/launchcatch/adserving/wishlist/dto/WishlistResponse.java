package com.launchcatch.adserving.wishlist.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record WishlistResponse(
        @Schema(description = "찜 목록. 찜한 시각 내림차순이다. 0건이면 빈 배열")
        List<WishlistItemResponse> wishlist
) {
}
