package com.launchcatch.adserving.feed.exception;

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
 * 429 의 Retry-After 를 남은 초로 채운다.
 * 계층 규칙상 exception 이 controller 를 참조하지 못해 패키지 이름으로 범위를 건다.
 * 공통 처리기는 1초를 넣는데, 그 값은 윈도우를 든 쪽만 알아서 여기서 넣는다(GlobalExceptionHandler 의 RETRY_AFTER_SECONDS).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.launchcatch.adserving.feed.controller")
public class FeedExceptionHandler {

    @ExceptionHandler(FeedRateLimitedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleRateLimited(FeedRateLimitedException e,
                                                                    HttpServletRequest request) {
        AccessLogSignal.markExpected(request);
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ResponseEnvelope.fail(e.getErrorCode()));
    }
}
