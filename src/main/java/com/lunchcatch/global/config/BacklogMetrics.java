package com.lunchcatch.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/*
 * 아웃박스 릴레이와 비동기 워커의 밀린 양을 공통 이름의 지표로 낸다(#1173, #1131).
 * 같은 것을 여러 곳에서 재면 이름을 하나로 두고 꼬리표로 나눈다. 06 처리 시간 목표 초과 대시보드가 쿼리 하나로 모두 그리고, 새 릴레이나 워커가 생겨도 대시보드를 고치지 않는다.
 * 이름은 기준 설정(lunchcatch.observability.targets 의 relay, worker)의 키와 같게 쓴다. 판정은 대기 시간으로 한다.
 */
@Component
public class BacklogMetrics {

    static final String RELAY_BACKLOG = "outbox.relay.backlog";
    static final String RELAY_OLDEST_AGE = "outbox.relay.oldest.age";
    static final String WORKER_QUEUE_SIZE = "async.worker.queue.size";
    static final String WORKER_OLDEST_AGE = "async.worker.oldest.age";

    private final MeterRegistry registry;
    private final Clock clock;

    public BacklogMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /*
     * 릴레이의 미발행 건수와 가장 이른 미발행 건의 시각을 받는다. 미발행이 없으면 시각은 null 이다.
     * 대기 시간은 긁을 때의 시각으로 센다. 릴레이가 멈춰 값을 갱신하지 않아도 대기 시간은 계속 늘어 멈춘 것이 드러난다.
     */
    public void relay(String name, LongSupplier backlog, Supplier<LocalDateTime> oldest) {
        Gauge.builder(RELAY_BACKLOG, backlog::getAsLong)
                .description("아웃박스 릴레이가 아직 넘기지 않은 건수")
                .tag("relay", name)
                .register(registry);
        Gauge.builder(RELAY_OLDEST_AGE, () -> ageSeconds(oldest.get()))
                .description("아웃박스 릴레이에서 가장 오래 기다린 미발행 건의 대기 시간")
                .baseUnit("seconds")
                .tag("relay", name)
                .register(registry);
    }

    // 워커의 대기열 길이와 대기열에서 가장 오래된 일의 시각을 받는다. 비었으면 시각은 null 이다
    public void worker(String name, LongSupplier queueSize, Supplier<LocalDateTime> oldest) {
        Gauge.builder(WORKER_QUEUE_SIZE, queueSize::getAsLong)
                .description("비동기 워커의 대기열 길이")
                .tag("worker", name)
                .register(registry);
        Gauge.builder(WORKER_OLDEST_AGE, () -> ageSeconds(oldest.get()))
                .description("비동기 워커의 대기열에서 가장 오래 기다린 일의 대기 시간")
                .baseUnit("seconds")
                .tag("worker", name)
                .register(registry);
    }

    private double ageSeconds(LocalDateTime oldest) {
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldest, LocalDateTime.now(clock)).toNanos() / 1e9);
    }
}
