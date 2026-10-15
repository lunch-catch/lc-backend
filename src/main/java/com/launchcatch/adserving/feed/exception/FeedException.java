package com.launchcatch.adserving.feed.exception;

import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.exception.ErrorCode;

public class FeedException extends BusinessException {

    public FeedException(ErrorCode errorCode) {
        super(errorCode);
    }
}
