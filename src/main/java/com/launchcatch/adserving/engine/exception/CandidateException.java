package com.launchcatch.adserving.engine.exception;

import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.exception.ErrorCode;

public class CandidateException extends BusinessException {

    public CandidateException(ErrorCode errorCode) {
        super(errorCode);
    }
}
