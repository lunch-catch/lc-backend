package com.lunchcatch.adserving.engine.exception;

import com.lunchcatch.global.exception.BusinessException;
import com.lunchcatch.global.exception.ErrorCode;

public class CandidateException extends BusinessException {

    public CandidateException(ErrorCode errorCode) {
        super(errorCode);
    }
}
