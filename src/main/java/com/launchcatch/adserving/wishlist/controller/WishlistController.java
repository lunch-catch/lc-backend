package com.launchcatch.adserving.wishlist.controller;

import com.launchcatch.adserving.wishlist.dto.WishlistResponse;
import com.launchcatch.adserving.wishlist.service.WishlistService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "찜 목록", description = "찜 목록 API")
public class WishlistController {

    private final WishlistService wishlistService;

    // 내 찜 목록을 조회한다. 쿠폰 잔여 수량과 발급 가능 여부는 화면이 쿠폰 API 로 따로 받는다.
    @GetMapping("/wishlist")
    @Operation(summary = "찜 목록 조회", description = "찜한 시각 내림차순이다. 오늘 노출 후보가 아닌 캠페인은 storeName, storeImageUrl, discount 가 null 이다.")
    @ApiResponse(responseCode = "200", description = "찜 목록을 반환한다. 0건이면 빈 배열이다.", useReturnTypeSchema = true)
    public ResponseEnvelope<WishlistResponse> getWishlist(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "주면 그 캠페인만 돌려준다. 찜하지 않았으면 빈 배열이다")
            @RequestParam(required = false) Long campaignId) {
        return ResponseEnvelope.success(wishlistService.getWishlist(user.getId(), campaignId));
    }
}
