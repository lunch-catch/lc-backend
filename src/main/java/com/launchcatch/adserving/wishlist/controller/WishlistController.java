package com.launchcatch.adserving.wishlist.controller;

import com.launchcatch.adserving.wishlist.dto.WishlistItemResponse;
import com.launchcatch.adserving.wishlist.dto.WishlistRequest;
import com.launchcatch.adserving.wishlist.dto.WishlistResponse;
import com.launchcatch.adserving.wishlist.service.WishResult;
import com.launchcatch.adserving.wishlist.service.WishlistService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "찜 목록", description = "찜 목록 API")
public class WishlistController {

    private final WishlistService wishlistService;

    /*
     * 내 찜 목록을 조회한다.
     * 쿠폰 잔여 수량과 발급 가능 여부는 화면이 쿠폰 API 로 따로 받는다.
     */
    @GetMapping("/wishlist")
    @Operation(summary = "찜 목록 조회", description = "찜한 시각 내림차순이다. 오늘 노출 후보가 아닌 캠페인은 storeName, storeImageUrl, discount 가 null 이다.")
    @ApiResponse(responseCode = "200", description = "찜 목록을 반환한다. 0건이면 빈 배열이다.", useReturnTypeSchema = true)
    public ResponseEnvelope<WishlistResponse> getWishlist(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "주면 그 캠페인만 돌려준다. 찜하지 않았으면 빈 배열이다")
            @RequestParam(required = false) Long campaignId) {
        return ResponseEnvelope.success(wishlistService.getWishlist(user.getId(), campaignId));
    }

    /*
     * 가게 상세에서 찜한다.
     * 피드의 찜은 POST /v1/swipes 로 한다.
     */
    @PostMapping("/wishlist")
    @Operation(summary = "가게 상세에서 찜", description = "처음 찜하면 201, 이미 찜한 캠페인이면 200 이다. 오늘 노출 중인 캠페인이 아니면 404 ADSERVING-004 다.")
    @ApiResponse(responseCode = "201", description = "찜 목록 항목 하나를 반환한다.", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "200", description = "이미 찜한 캠페인이다. 처음 찜한 시각을 담은 항목을 반환한다.", useReturnTypeSchema = true)
    public ResponseEntity<ResponseEnvelope<WishlistItemResponse>> wish(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody WishlistRequest request) {
        WishResult result = wishlistService.wish(user.getId(), request.campaignId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ResponseEnvelope.success(result.item()));
    }

    // 찜 목록에서 지운다. 지운 캠페인은 그날 피드에 다시 나오지 않는다.
    @DeleteMapping("/wishlist/{campaignId}")
    @Operation(summary = "찜 삭제", description = "찜 목록에 없는 캠페인이어도 204 다. 지운 캠페인은 그날 피드에 다시 나오지 않는다.")
    @ApiResponse(responseCode = "204", description = "본문이 없다.")
    public ResponseEntity<Void> unwish(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "찜을 지울 캠페인") @PathVariable Long campaignId) {
        wishlistService.unwish(user.getId(), campaignId);
        return ResponseEntity.noContent().build();
    }
}
