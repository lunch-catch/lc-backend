package com.launchcatch.analytics.batch.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/*
 * 집계 배치의 지표다. 라벨은 step, result, check 뿐이고 회원이나 캠페인 id 는 넣지 않는다.
 *
 * 게이지는 레지스트리가 값을 약하게만 쥐므로 AtomicLong 을 이 객체가 직접 들고 있는다.
 */
@Component
public class AggregationMetrics {

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> rows = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> violations = new ConcurrentHashMap<>();
    private final AtomicLong pendingDates = new AtomicLong();

    public AggregationMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("analytics_aggregation_pending_dates", pendingDates);
    }

    public void recordStep(String step, Duration elapsed) {
        registry.timer("analytics_aggregation_step_seconds", "step", step).record(elapsed);
    }

    public void recordRows(String step, long count) {
        gauge(rows, "analytics_aggregation_rows", "step", step).set(count);
    }

    public void countResult(String result) {
        registry.counter("analytics_aggregation_total", "result", result).increment();
    }

    public void recordPendingDates(long count) {
        pendingDates.set(count);
    }

    public void recordViolations(String check, long count) {
        gauge(violations, "analytics_consistency_violations", "check", check).set(count);
    }

    private AtomicLong gauge(Map<String, AtomicLong> holders, String name, String tagKey, String tagValue) {
        return holders.computeIfAbsent(tagValue, value -> {
            AtomicLong holder = new AtomicLong();
            registry.gauge(name, Tags.of(tagKey, value), holder);
            return holder;
        });
    }
}
