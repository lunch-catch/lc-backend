package com.launchcatch.analytics.exception;

import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.exception.ErrorCode;

public class AnalyticsException extends BusinessException {

    public AnalyticsException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AnalyticsException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
