package com.launchcatch.admin.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AdminErrorCode implements ErrorCode {
    LOGIN_ID_DUPLICATED(HttpStatus.CONFLICT, "ADMIN-001", "이미 사용 중인 관리자 ID입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}