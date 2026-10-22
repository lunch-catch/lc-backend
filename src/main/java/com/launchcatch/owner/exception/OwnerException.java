package com.launchcatch.owner.exception;

import com.launchcatch.global.exception.BusinessException;

public class OwnerException extends BusinessException {
    public OwnerException(OwnerErrorCode errorCode) {
        super(errorCode);
    }
}