package com.lunchcatch.ops.exception;

import com.lunchcatch.global.exception.BusinessException;
import com.lunchcatch.global.exception.ErrorCode;

public class OpsException extends BusinessException {

    public OpsException(ErrorCode errorCode) {
        super(errorCode);
    }

    public OpsException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
