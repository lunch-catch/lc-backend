package com.launchcatch.auth.exception;

import com.launchcatch.global.exception.BusinessException;

/*
 * 인증과 인가의 실패를 던질 때 쓴다. 코드는 AuthErrorCode 가 들고 있다.
 * BusinessException 이 추상이라 도메인마다 구체 타입이 하나씩 필요하다.
 */
public class AuthException extends BusinessException {

    public AuthException(AuthErrorCode errorCode) {
        super(errorCode);
    }

    /* 외부 호출이나 하위 계층의 예외를 옮길 때 쓴다. 세션 저장 실패가 그 경우다. */
    public AuthException(AuthErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
