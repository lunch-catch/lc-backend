package com.launchcatch.store.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StoreErrorCode implements ErrorCode {
    TERMS_ALREADY_RECORDED(HttpStatus.CONFLICT, "STORE-001", "이미 저장된 약관 동의 내역입니다."),
    REQUIRED_TERMS_NOT_COMPLETED(HttpStatus.UNPROCESSABLE_ENTITY, "STORE-002", "필수 약관에 모두 동의해야 합니다."),
    TERMS_POLICY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "STORE-003", "약관 정책을 준비 중입니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}