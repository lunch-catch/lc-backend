package com.launchcatch.ops.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OpsErrorCode implements ErrorCode {

    NOT_RETRYABLE_JOB(HttpStatus.UNPROCESSABLE_ENTITY, "OPS-001",
            "다시 실행할 수 없는 작업입니다."),
    EXECUTION_NOT_FOUND(HttpStatus.NOT_FOUND, "OPS-002",
            "그 작업의 실행 기록이 없습니다."),
    EXECUTION_NOT_FAILED(HttpStatus.CONFLICT, "OPS-003",
            "FAILED 상태가 아니면 재실행할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
