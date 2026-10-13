package com.launchcatch.owner.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OwnerErrorCode implements ErrorCode {
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "OWNER-001", "이미 사용 중인 이메일입니다."),
    OWNER_STATE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "OWNER-002", "현재 점주 상태에서는 가게를 검색할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
