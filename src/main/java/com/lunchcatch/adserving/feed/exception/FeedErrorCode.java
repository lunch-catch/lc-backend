package com.lunchcatch.adserving.feed.exception;

import com.lunchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum FeedErrorCode implements ErrorCode {

    ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN, "ADSERVING-001", "온보딩을 완료한 뒤 피드를 이용할 수 있습니다."),
    LOCATION_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "ADSERVING-002", "위치를 설정한 뒤 피드를 이용할 수 있습니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "ADSERVING-003", "잠시 후 다시 시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
