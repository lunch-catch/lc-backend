package com.lunchcatch.coupon.config;

import com.lunchcatch.coupon.exception.CouponErrorCode;
import com.lunchcatch.coupon.exception.CouponException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Component;

// HTTP 전달 여부와 별개로 발급 서비스가 확정한 최종 결과를 요청당 한 번 집계한다.
@Component
public class CouponIssuanceMetrics {

    private final Counter success;
    private final Counter systemError;
    private final Map<CouponErrorCode, Counter> rejected = new EnumMap<>(CouponErrorCode.class);

    public CouponIssuanceMetrics(MeterRegistry registry) {
        success = registry.counter("coupon.issue.results", "result", "SUCCESS");
        systemError = registry.counter("coupon.issue.results", "result", "SYSTEM_ERROR");
        for (CouponErrorCode code : CouponErrorCode.values()) {
            rejected.put(code, registry.counter("coupon.issue.results", "result", code.getCode()));
        }
    }

    public void record(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error == null) {
            success.increment();
        } else if (error instanceof CouponException couponError
                && couponError.getErrorCode() instanceof CouponErrorCode code) {
            rejected.get(code).increment();
        } else {
            systemError.increment();
        }
    }
}
