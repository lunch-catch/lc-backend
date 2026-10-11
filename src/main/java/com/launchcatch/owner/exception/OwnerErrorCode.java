package com.launchcatch.owner.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OwnerErrorCode implements ErrorCode {
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "OWNER-001", "이미 사용 중인 이메일입니다."),
    // 점주 상태가 'ACTIVE'가 아니면, 'STATUS_NOT_ALLOWED' 오류를 반환한다.
    STATUS_NOT_ALLOWED(HttpStatus.FORBIDDEN, "OWNER-002", "현재 점주 상태에서는 요청한 기능을 사용할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}