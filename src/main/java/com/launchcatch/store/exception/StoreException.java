package com.launchcatch.store.exception;

import com.launchcatch.global.exception.BusinessException;

public class StoreException extends BusinessException {
    public StoreException(StoreErrorCode code) { super(code); }
    public StoreException(StoreErrorCode code, Throwable cause) { super(code, cause); }
}