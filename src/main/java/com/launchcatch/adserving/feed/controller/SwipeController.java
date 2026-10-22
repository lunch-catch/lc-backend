package com.launchcatch.adserving.feed.controller;

import com.launchcatch.adserving.feed.dto.SwipeRequest;
import com.launchcatch.adserving.feed.dto.SwipeResponse;
import com.launchcatch.adserving.feed.service.SwipeService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "피드", description = "스와이프 피드 API")
public class SwipeController {

    private final SwipeService swipeService;

    // 카드를 찜하거나 패스한다. 같은 카드를 다시 보내면 반영하지 않고 applied false 로 성공을 돌려준다.
    @PostMapping("/swipes")
    @Operation(summary = "찜 또는 패스", description = "같은 카드는 처음 넘긴 것 하나만 반영한다. 찜한 캠페인과 패스한 캠페인은 그날 피드에 다시 나오지 않는다.")
    @ApiResponse(responseCode = "200", description = "이번 요청이 반영됐는지 반환한다.", useReturnTypeSchema = true)
    public ResponseEnvelope<SwipeResponse> swipe(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody SwipeRequest request) {
        return ResponseEnvelope.success(swipeService.swipe(user.getId(), request));
    }
}
