package com.lunchcatch.store.exception;

import com.lunchcatch.global.exception.BusinessException;

public class StoreException extends BusinessException {
    public StoreException(StoreErrorCode code) {
        super(code);
    }

    public StoreException(StoreErrorCode code, Throwable cause) {
        super(code, cause);
    }
}
