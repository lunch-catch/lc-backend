package com.launchcatch.coupon.exception;

import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.exception.ErrorCode;

public class CouponException extends BusinessException {

    private Long recoveryEventId;
    private boolean issuanceNotCommitted;

    public CouponException(ErrorCode errorCode) {
        super(errorCode);
    }

    public CouponException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    // 순번 발급기의 오류가 난 이벤트를 기록하며 미발급 확인 전에는 재접수를 허용하지 않는다.
    public static CouponException recoveryRequired(long eventId) {
        CouponException error = new CouponException(CouponErrorCode.ISSUANCE_BUSY);
        error.recoveryEventId = eventId;
        return error;
    }

    // 발급 전 거절이나 확정된 트랜잭션 종료에서 해당 요청의 미발급을 확인한다.
    public void confirmIssuanceNotCommitted() {
        issuanceNotCommitted = true;
    }

    // 오류 코드가 같아도 미발급과 복구 대상 이벤트가 확인된 요청만 재접수한다.
    public boolean canResumeIssuance(long eventId) {
        return issuanceNotCommitted && recoveryEventId != null && recoveryEventId == eventId;
    }
}
