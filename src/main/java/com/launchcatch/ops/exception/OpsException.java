package com.launchcatch.ops.exception;

import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.exception.ErrorCode;

public class OpsException extends BusinessException {

    public OpsException(ErrorCode errorCode) {
        super(errorCode);
    }

    public OpsException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
