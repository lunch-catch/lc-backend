package com.lunchcatch.owner.exception;

import com.lunchcatch.global.exception.BusinessException;

public class OwnerException extends BusinessException {
    public OwnerException(OwnerErrorCode errorCode) {
        super(errorCode);
    }
}