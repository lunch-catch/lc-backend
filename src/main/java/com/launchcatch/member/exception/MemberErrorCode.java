package com.launchcatch.member.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 사용자 도메인의 실패 코드. 명세는 docs/api-spec/auth.md 의 "오류 코드" 절이다.
 *
 * 사용자의 로그인 수단이 카카오뿐이라 로그인이 곧 가입이다. 그 경로의 실패는 사용자
 * 도메인이 아는 실패다. 재발급과 로그아웃의 실패는 AuthErrorCode 를 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    /*
     * 카카오 인가 코드 교환이나 ID 토큰 검증이 실패했다.
     * 벤더 쪽 응답을 그대로 내보내지 않는다. 어느 단계에서 틀렸는지는 로그에만 남긴다.
     */
    KAKAO_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "MEMBER-001",
            "카카오 로그인에 실패했습니다."),

    /*
     * 정지 이력이 있는 회원번호로 다시 가입을 시도했다.
     * 왜 막혔는지는 알려 주지 않는다. 알려 주면 어느 계정이 정지되었는지 드러난다.
     */
    SIGNUP_NOT_ALLOWED(HttpStatus.FORBIDDEN, "MEMBER-002",
            "가입할 수 없는 계정입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
