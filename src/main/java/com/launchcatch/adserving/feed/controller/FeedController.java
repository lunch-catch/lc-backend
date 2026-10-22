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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "피드", description = "스와이프 피드 API")
public class FeedController {

    private final FeedService feedService;

    // 현재 위치 기준 카드 10장을 내려준다. 시간대 제한 없이 하루 종일 받는다.
    @GetMapping("/feed")
    @Operation(summary = "스와이프 피드 조회", description = "위도와 경도를 모두 생략하면 저장 위치를 쓴다. 사용자마다 최근 60초 동안 6회까지 받는다.")
    @ApiResponse(responseCode = "200", description = "피드 상태와 카드 목록을 반환한다.", useReturnTypeSchema = true)
    public ResponseEnvelope<FeedResponse> getFeed(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute FeedRequest request) {
        return ResponseEnvelope.success(feedService.getFeed(user.getId(), request));
    }

    /*
     * 429 의 Retry-After 는 다시 요청할 수 있을 때까지 남은 초여야 하고 그 값은 요청 시각을 들고 있는 이쪽만 안다.
     * GlobalExceptionHandler 는 상태 코드만 보고 1초를 붙이므로 이 예외만 여기서 받는다. 봉투와 문구는 같은 ErrorCode 에서 나온다.
     */
    @ExceptionHandler(FeedRateLimitedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleRateLimited(FeedRateLimitedException e) {
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ResponseEnvelope.fail(e.getErrorCode()));
    }
}
