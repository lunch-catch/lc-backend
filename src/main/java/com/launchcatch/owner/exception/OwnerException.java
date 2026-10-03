package com.launchcatch.owner.exception;

import com.launchcatch.global.exception.BusinessException;

/* 점주 도메인의 실패를 던질 때 쓴다. 코드는 OwnerErrorCode 가 들고 있다. */
public class OwnerException extends BusinessException {

    public OwnerException(OwnerErrorCode errorCode) {
        super(errorCode);
    }

    public OwnerException(OwnerErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
