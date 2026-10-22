package com.launchcatch.admin.exception;

import com.launchcatch.global.exception.BusinessException;

public class AdminException extends BusinessException {
    public AdminException(AdminErrorCode errorCode) {
        super(errorCode);
    }

    public AdminException(AdminErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}