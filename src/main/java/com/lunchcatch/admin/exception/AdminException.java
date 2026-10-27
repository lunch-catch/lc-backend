package com.lunchcatch.admin.exception;

import com.lunchcatch.global.exception.BusinessException;

public class AdminException extends BusinessException {
    public AdminException(AdminErrorCode errorCode) {
        super(errorCode);
    }

    public AdminException(AdminErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}