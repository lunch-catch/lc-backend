package com.launchcatch.adserving.feed.controller;

import com.launchcatch.adserving.feed.dto.FeedRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.service.FeedService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "광고 서빙", description = "스와이프 피드 API")
public class FeedController {

    private final FeedService feedService;

    // 서빙 시간대에 현재 위치 기준 카드 10장을 내려준다
    @GetMapping("/feed")
    @Operation(summary = "스와이프 피드 조회", description = "위치를 보내지 않으면 저장 위치를 쓴다. 최근 60초에 6회까지 받는다.")
    @ApiResponse(responseCode = "200", description = "피드 상태와 카드 목록을 반환한다.", useReturnTypeSchema = true)
    public ResponseEnvelope<FeedResponse> getFeed(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @ParameterObject @Valid FeedRequest request) {
        return ResponseEnvelope.success(feedService.getFeed(user.getId(), request.latitude(), request.longitude()));
    }

    /*
     * Retry-After 에 다시 요청할 수 있을 때까지 남은 초를 싣는다.
     * 공통 처리기는 그 값을 모르고 최소값 1초만 붙이므로 창을 아는 이 경로가 직접 답한다.
     * 정한 제한에 걸린 것이라 로그를 남기지 않는다.
     */
    @ExceptionHandler(FeedRateLimitedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleRateLimited(FeedRateLimitedException e) {
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ResponseEnvelope.fail(e.getErrorCode()));
    }
}
