package com.launchcatch.auth.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 인증과 인가의 실패 코드. 명세는 docs/api-spec/auth.md 의 "오류 코드" 절이다.
 *
 * 역할이 셋이지만 정책이 하나라 코드도 하나로 둔다. 점주 가입과 카카오 로그인의 실패는
 * 그 도메인이 아는 실패라 owner 와 member 의 ErrorCode 로 간다.
 *
 * 점주 상태가 ONBOARDING 이라 막는 것도 여기 두지 않는다. 이 모듈은 Role 만 알고
 * 점주 상태는 점주 도메인의 데이터다. 그 코드는 점주 도메인이 소유한다.
 *
 * 사유를 구분하지 않는 코드가 둘 있다. AUTH-002 와 AUTH-004 다. 계정이 없는 것과 비밀번호가
 * 틀린 것과 쓸 수 없는 상태인 것을 한 코드로 묶는다. 구분해 주면 어느 아이디가 존재하는지
 * 알려주는 통로가 된다.
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "AUTH-001",
            "요청 값이 올바르지 않습니다."),

    /* 계정 없음, 비밀번호 불일치, 사용 불가 상태를 구분하지 않는다. */
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH-002",
            "아이디 또는 비밀번호가 올바르지 않습니다."),

    /*
     * 세션 저장과 폐기가 실패한 경우다. Valkey 가 닫혀 있으면 로그인과 로그아웃이 여기로 온다.
     * 5xx 라 handleBusiness 가 ERROR 로 남기고, toResponse 가 Retry-After 를 붙인다.
     */
    SESSION_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH-003",
            "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    /*
     * Refresh Token 이 없거나 유효하지 않거나 만료됐거나 폐기된 경우다.
     *
     * 재발급은 수명이 다한 토큰으로 계속 들어온다. 정상 운영에서 예상되는 답이라
     * 로그로 남기지 않는다(isExpectedTraffic). 몇 건인지는 지표가 센다.
     */
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH-004",
            "다시 로그인해 주세요.") {
        @Override
        public boolean isExpectedTraffic() {
            return true;
        }
    },

    /*
     * 이미 교체된 Refresh Token 이 다시 온 경우다. 탈취로 보고 그 계정의 세션을 모두 끊는다.
     * 드물고 심각하므로 로그를 남긴다.
     */
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "AUTH-005",
            "보안을 위해 모든 기기에서 로그아웃되었습니다. 다시 로그인해 주세요."),

    /*
     * Access Token 이 없거나 유효하지 않거나 로그아웃 전에 발급된 경우다.
     *
     * Access 가 30분이라 사용자마다 30분에 한 번은 이 응답을 받는다. 그것이 설계대로 도는
     * 모습이므로 로그로 남기지 않는다. 클라이언트는 이 코드를 보고 재발급을 한 번 부른다
     * (api-spec/README.md 의 인증 절).
     */
    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH-006",
            "로그인이 필요합니다.") {
        @Override
        public boolean isExpectedTraffic() {
            return true;
        }
    },

    /* 역할이 맞지 않는다. 대상의 존재 여부를 드러내지 않도록 상세를 담지 않는다. */
    ROLE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "AUTH-007",
            "접근 권한이 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
