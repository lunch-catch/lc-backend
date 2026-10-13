package com.launchcatch.adserving.feed.controller;

import com.launchcatch.adserving.feed.dto.FeedRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.service.FeedService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
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
public class FeedController {

    private final FeedService feedService;

    // 서빙 시간대에 현재 위치 기준 카드 10장을 내려준다
    @GetMapping("/feed")
    public ResponseEnvelope<FeedResponse> getFeed(
            @AuthenticationPrincipal CustomUserDetails user, @Valid @ModelAttribute FeedRequest request) {
        return ResponseEnvelope.success(feedService.getFeed(user.getId(), request));
    }
}
