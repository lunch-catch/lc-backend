package com.launchcatch.global.exception;

import lombok.Getter;

// 비즈니스 정책 위반을 나타내는 공통 예외 기반 클래스
@Getter
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /*
     * 하위 계층이나 외부 호출의 예외를 도메인 실패로 옮길 때 쓴다.
     * cause 를 넘겨야 스택이 끊기지 않는다. 원인 예외가 없으면 위 생성자를 쓴다.
     */
    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
