package com.lunchcatch.analytics.exception;

import com.lunchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AnalyticsErrorCode implements ErrorCode {

    INVALID_PERIOD(HttpStatus.BAD_REQUEST, "ANALYTICS-001", "조회 기간이 올바르지 않습니다."),
    UNAGGREGATABLE_DATE(HttpStatus.UNPROCESSABLE_ENTITY, "ANALYTICS-002",
            "집계할 수 없는 날짜입니다."),
    AGGREGATION_ALREADY_RUNNING(HttpStatus.CONFLICT, "ANALYTICS-003",
            "해당 날짜의 집계가 이미 실행 중입니다."),
    UNSUPPORTED_EVENT_TYPE(HttpStatus.BAD_REQUEST, "ANALYTICS-004",
            "허용되지 않은 이벤트 유형입니다."),
    INVALID_EVENT_REQUEST(HttpStatus.BAD_REQUEST, "ANALYTICS-005",
            "이벤트 요청이 올바르지 않습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
