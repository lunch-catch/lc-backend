package com.lunchcatch.billing.payment.exception;

import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import lombok.Getter;

// 점주별 충전 상한을 넘은 요청이다. 다시 요청할 수 있을 때까지 남은 초를 Retry-After 헤더로 싣는다.
@Getter
public class ChargeRateLimitedException extends BillingException {

    private final long retryAfterSeconds;

    public ChargeRateLimitedException(long retryAfterSeconds) {
        super(BillingErrorCode.CHARGE_RATE_LIMITED);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
