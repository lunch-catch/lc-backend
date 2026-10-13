package com.launchcatch.store.exception;

import com.launchcatch.global.exception.BusinessException;

public class StoreException extends BusinessException {
    public StoreException(StoreErrorCode errorCode) {
        super(errorCode);
    }

    public StoreException(StoreErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
