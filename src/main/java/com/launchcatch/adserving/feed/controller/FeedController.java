package com.launchcatch.adserving.feed.controller;

import com.launchcatch.adserving.feed.dto.FeedLocationRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.service.FeedService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "피드", description = "스와이프 피드 API")
@SecurityRequirement(name = "memberAccessToken")
public class FeedController {

    private final FeedService feedService;

    @GetMapping("/feed")
    @Operation(summary = "스와이프 피드 조회",
            description = "서빙 시간대(10:00~12:59)에 현재 위치 기준 카드 10장을 내려준다. 위치를 보내지 않으면 저장 위치를 쓴다.")
    @ApiResponse(responseCode = "200", description = "피드 한 묶음을 반환한다.", useReturnTypeSchema = true)
    public ResponseEnvelope<FeedResponse> getFeed(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute FeedLocationRequest location) {
        return ResponseEnvelope.success(feedService.getFeed(user.getId(), location));
    }
}
