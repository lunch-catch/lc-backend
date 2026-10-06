package com.launchcatch.member.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    ALREADY_WITHDRAWN(HttpStatus.BAD_REQUEST, "MEMBER-001", "이미 탈퇴한 회원입니다."),
    ONBOARDING_ALREADY_COMPLETED(HttpStatus.CONFLICT, "MEMBER-002", "이미 온보딩을 완료한 회원입니다."),
    SUSPENDED_REJOIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "MEMBER-003", "정지 이력이 있는 회원은 재가입할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
