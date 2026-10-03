package com.launchcatch.owner.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 점주 도메인의 실패 코드. 명세는 docs/api-spec/auth.md 의 "오류 코드" 절이다.
 *
 * 점주 회원가입이 로그인 전에 열린 경로라 auth.md 가 함께 적지만, 가입의 실패는
 * 점주 도메인이 아는 실패다. 로그인과 재발급의 실패는 AuthErrorCode 를 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum OwnerErrorCode implements ErrorCode {

    /* 이메일 형식 오류, 비밀번호 형식 오류, 필수값 누락을 한 코드로 묶는다. */
    INVALID_SIGNUP_REQUEST(HttpStatus.BAD_REQUEST, "OWNER-001",
            "이메일 또는 비밀번호 형식이 올바르지 않습니다."),

    /*
     * 이미 가입한 이메일이다.
     *
     * 로그인 실패와 달리 사유를 알려 준다. 가입 화면은 중복을 알려 주지 않으면 진행할 수
     * 없고, 어느 이메일이 가입되어 있는지는 가입 시도만으로도 드러나는 정보다.
     */
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "OWNER-002",
            "이미 가입된 이메일입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
