package com.lunchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CouponLogContextTest {
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 접수_후_호출자_MDC가_바뀌어도_복사한_요청_정보를_유지한다() {
        MDC.put("traceId", "request-1");
        CouponLogContext captured = CouponLogContext.capture();
        MDC.put("traceId", "request-2");

        try (var ignored = captured.open()) {
            assertThat(MDC.get("traceId")).isEqualTo("request-1");
        }
        assertThat(MDC.get("traceId")).isEqualTo("request-2");
    }

    @Test
    void 작업이_예외로_끝나도_진입_전의_MDC를_복원한다() {
        MDC.put("traceId", "worker");
        assertThatThrownBy(() -> {
            try (var ignored = new CouponLogContext(Map.of("traceId", "request")).open()) {
                throw new IllegalStateException("failed");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("traceId", "worker"));
    }

    @Test
    void 원래_MDC가_없으면_작업_정보를_남기지_않는다() {
        MDC.clear();
        CouponLogContext empty = CouponLogContext.capture();
        try (var ignored = new CouponLogContext(Map.of("traceId", "request")).open()) {
            assertThat(MDC.get("traceId")).isEqualTo("request");
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        try (var ignored = empty.open()) {
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }
    }

    @Test
    void 공유_작업은_새_추적_ID와_중복을_제거한_원본_요청_ID를_갖는다() {
        Map<String, String> request = new HashMap<>(Map.of("traceId", "request-1", "clientIp", "127.0.0.1"));
        CouponLogContext first = new CouponLogContext(request);
        request.put("traceId", "changed");
        CouponLogContext context = CouponLogContext.shared(List.of(first, first,
                new CouponLogContext(Map.of("traceId", "request-2")), new CouponLogContext(null)));

        try (var ignored = context.open()) {
            assertThat(MDC.get("traceId")).matches("[a-f0-9]{32}");
            assertThat(CouponLogContext.requestTraceIds()).isEqualTo("request-1,request-2");
            assertThat(MDC.get("clientIp")).isNull();
        }
        CouponLogContext nested = CouponLogContext.shared(List.of(context));
        assertThat(nested.values().get("requestTraceIds")).isEqualTo("request-1,request-2");
        assertThat(CouponLogContext.shared(List.of(new CouponLogContext(Map.of("traceId", " ")))).values())
                .containsEntry("requestTraceIds", "NONE");
    }
}
