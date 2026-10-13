package com.launchcatch.adserving.feed.controller;

import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.global.logging.AccessLogSignal;
import com.launchcatch.global.response.ResponseEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/*
 * 피드 요청 제한의 429 에 남은 초를 싣는다.
 * GlobalExceptionHandler 는 429 에 최소값 1초만 붙인다. 창을 들고 있는 쪽만 남은 초를 알아 여기서 넣는다.
 * 같은 예외를 global 이 BusinessException 으로도 받을 수 있어 순서를 앞에 둔다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = FeedController.class)
public class FeedExceptionHandler {

    @ExceptionHandler(FeedRateLimitedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleRateLimited(
            FeedRateLimitedException e, HttpServletRequest request) {
        AccessLogSignal.markExpected(request);
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ResponseEnvelope.fail(e.getErrorCode()));
    }
}
