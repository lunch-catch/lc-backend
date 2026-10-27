package com.lunchcatch.analytics.batch.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.List;
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
        // 첫 사건 전에도 시리즈가 있어야 increase() 가 첫 증가를 센다
        for (String result : List.of("success", "failed", "busy", "lease_lost", "source_not_ready")) {
            registry.counter("analytics_aggregation_total", "result", result);
        }
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

    /*
     * 한 실행 안에서는 날짜마다 찾은 위반 수 중 가장 큰 값을 남긴다.
     * 덮어쓰면 앞 날짜의 위반이 뒤 날짜의 0 에 지워지고, 더하면 같은 날짜를 다시 시도할 때 두 번 센다.
     */
    public void recordViolations(String check, long count) {
        gauge(violations, "analytics_consistency_violations", "check", check).accumulateAndGet(count, Math::max);
    }

    /*
     * 집계 실행을 시작할 때 모든 검사의 위반 수를 0 으로 되돌린다.
     * 되돌리지 않으면 위반을 찾은 서버에 다음 실행까지 지난 값이 남는다.
     */
    public void resetViolations() {
        violations.values().forEach(holder -> holder.set(0));
    }

    private AtomicLong gauge(Map<String, AtomicLong> holders, String name, String tagKey, String tagValue) {
        return holders.computeIfAbsent(tagValue, value -> {
            AtomicLong holder = new AtomicLong();
            registry.gauge(name, Tags.of(tagKey, value), holder);
            return holder;
        });
    }
}
