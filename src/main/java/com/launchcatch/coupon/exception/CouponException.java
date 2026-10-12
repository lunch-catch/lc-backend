package com.launchcatch.coupon.exception;

import com.launchcatch.global.exception.BusinessException;

public class CouponException extends BusinessException {

    public CouponException(CouponErrorCode errorCode) {
        super(errorCode);
    }
}
