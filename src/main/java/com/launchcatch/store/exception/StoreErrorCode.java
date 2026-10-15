package com.launchcatch.store.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StoreErrorCode implements ErrorCode {
    REGISTRATION_VERSION_CONFLICT(HttpStatus.CONFLICT, "STORE-001", "다른 화면에서 정보가 변경되었습니다. 최신 정보를 확인해 주세요."),
    REGISTRATION_STEP_CONFLICT(HttpStatus.CONFLICT, "STORE-002", "현재 진행 단계와 요청한 단계가 일치하지 않습니다."),
    REGISTRATION_PROGRESS_NOT_FOUND(HttpStatus.CONFLICT, "STORE-003", "가게 등록 진행 상태를 먼저 초기화해야 합니다."),
    REGISTRATION_STORE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "STORE-004", "해당 가게를 등록 진행 상태에 연결할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}