package com.lunchcatch.coupon.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.MDC;

// 요청의 MDC를 보관하고 여러 요청의 공유 작업에는 별도 추적 ID를 부여한다.
record CouponLogContext(Map<String, String> values) {

    CouponLogContext {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    static CouponLogContext capture() {
        return new CouponLogContext(MDC.getCopyOfContextMap());
    }

    static CouponLogContext shared(List<CouponLogContext> requests) {
        String traceIds = requests.stream()
                .map(context -> context.values.getOrDefault("requestTraceIds", context.values.get("traceId")))
                .filter(value -> value != null && !value.isBlank())
                .flatMap(value -> Arrays.stream(value.split(",")))
                .distinct().collect(Collectors.joining(","));
        return new CouponLogContext(Map.of("traceId", UUID.randomUUID().toString().replace("-", ""),
                "requestTraceIds", traceIds.isEmpty() ? "NONE" : traceIds));
    }

    Scope open() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.setContextMap(values);
        return new Scope(previous);
    }

    static String requestTraceIds() {
        return MDC.get("requestTraceIds");
    }

    record Scope(Map<String, String> previous) implements AutoCloseable {
        @Override
        public void close() {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }
}
