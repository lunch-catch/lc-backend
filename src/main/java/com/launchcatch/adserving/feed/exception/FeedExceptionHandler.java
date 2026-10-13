package com.launchcatch.adserving.feed.exception;

import com.launchcatch.global.logging.AccessLogSignal;
import com.launchcatch.global.response.ResponseEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/*
 * 피드 요청 제한의 429 에 Retry-After 를 붙인다.
 * GlobalExceptionHandler 는 429 에 최소값 1초를 붙이는데, 남은 초는 창을 들고 있는 광고 서빙만 안다.
 * GlobalExceptionHandler 는 Order 가 없어 가장 뒤라 이 advice 가 먼저 고른다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.launchcatch.adserving.feed")
public class FeedExceptionHandler {

    @ExceptionHandler(FeedRateLimitedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleRateLimited(
            FeedRateLimitedException e, HttpServletRequest request) {
        AccessLogSignal.markExpected(request);
        log.debug("business exception. code={}", e.getErrorCode().getCode());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ResponseEnvelope.fail(e.getErrorCode()));
    }
}
