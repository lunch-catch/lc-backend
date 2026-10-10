package com.launchcatch.billing.exception;

import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.exception.ErrorCode;

/*
 * 정산 도메인의 정책 위반이다.
 * 실패 종류마다 예외를 만들지 않고 이 하나에 ErrorCode 를 담는다.
 */
public class BillingException extends BusinessException {

    public BillingException(ErrorCode errorCode) {
        super(errorCode);
    }

    /*
     * 외부 호출이나 하위 계층의 예외를 정산의 실패로 옮길 때 쓴다.
     * cause 를 넘겨야 스택이 끊기지 않는다. 원인 예외가 없으면 위 생성자를 쓴다.
     */
    public BillingException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
