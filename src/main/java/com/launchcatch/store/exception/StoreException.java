package com.launchcatch.store.exception;

import com.launchcatch.global.exception.BusinessException;

public class StoreException extends BusinessException {
    public StoreException(StoreErrorCode code) {
        super(code);
    }
}