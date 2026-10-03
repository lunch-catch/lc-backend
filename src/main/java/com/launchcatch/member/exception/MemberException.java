package com.launchcatch.member.exception;

import com.launchcatch.global.exception.BusinessException;

/* 사용자 도메인의 실패를 던질 때 쓴다. 코드는 MemberErrorCode 가 들고 있다. */
public class MemberException extends BusinessException {

    public MemberException(MemberErrorCode errorCode) {
        super(errorCode);
    }

    public MemberException(MemberErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
