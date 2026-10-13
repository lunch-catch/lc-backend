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

    /**
     * 서빙 시간대에 요청 위치나 저장 위치 기준으로 스와이프 피드 카드를 최대 10장 내려준다.
     *
     * @param user 인증된 회원 정보
     * @param request 현재 위도와 경도. 둘 다 없으면 저장 위치를 쓴다
     * @return 피드 상태와 카드 목록을 담은 성공 응답
     */
    @GetMapping("/feed")
    public ResponseEnvelope<FeedResponse> getFeed(
            @AuthenticationPrincipal CustomUserDetails user, @Valid @ModelAttribute FeedRequest request) {
        return ResponseEnvelope.success(feedService.getFeed(user.getId(), request.latitude(), request.longitude()));
    }
}
