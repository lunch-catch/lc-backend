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
    REGISTRATION_STORE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "STORE-004", "해당 가게를 등록 진행 상태에 연결할 수 없습니다."),
    TERMS_ALREADY_RECORDED(HttpStatus.CONFLICT, "STORE-005", "이미 저장된 약관 동의 내역입니다."),
    REQUIRED_TERMS_NOT_COMPLETED(HttpStatus.UNPROCESSABLE_ENTITY, "STORE-006", "필수 약관에 모두 동의해야 합니다."),
    TERMS_POLICY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "STORE-007", "약관 정책을 준비 중입니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}