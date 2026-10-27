package com.lunchcatch.adserving.feed.exception;

import com.lunchcatch.global.exception.BusinessException;
import com.lunchcatch.global.exception.ErrorCode;

public class FeedException extends BusinessException {

    public FeedException(ErrorCode errorCode) {
        super(errorCode);
    }
}
