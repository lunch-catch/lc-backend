package com.launchcatch.coupon.exception;

import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.exception.ErrorCode;

public class CouponException extends BusinessException {

    public CouponException(ErrorCode errorCode) {
        super(errorCode);
    }

    public CouponException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
