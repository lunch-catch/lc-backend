package com.lunchcatch.analytics.exception;

import com.lunchcatch.global.exception.BusinessException;
import com.lunchcatch.global.exception.ErrorCode;

public class AnalyticsException extends BusinessException {

    public AnalyticsException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AnalyticsException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
