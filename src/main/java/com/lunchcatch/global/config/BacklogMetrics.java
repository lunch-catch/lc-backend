package com.lunchcatch.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 * 릴레이의 대기 시간은 지금 넘길 수 있는 건만으로 잰다(#1205). 다시 넘길 시각을 기다리는 건과 포기한 건은 넣지 않는다.
 * 넣으면 재시도 중인 건 하나로도 기준을 넘고, 포기한 건이 남아 대기 시간이 끝없이 자란다. 포기한 건은 따로 센다.
 */
@Component
public class BacklogMetrics {

    static final String RELAY_BACKLOG = "outbox.relay.backlog";
    static final String RELAY_OLDEST_AGE = "outbox.relay.oldest.age";
    static final String RELAY_ABANDONED = "outbox.relay.abandoned";
    static final String RELAY_PUBLISH_DELAY = "outbox.relay.publish.delay";
    private static final Duration PUBLISH_DELAY_TARGET = Duration.ofSeconds(5);
    private static final Duration PUBLISH_DELAY_MAX = Duration.ofMinutes(15);
    static final String WORKER_QUEUE_SIZE = "async.worker.queue.size";
    static final String WORKER_OLDEST_AGE = "async.worker.oldest.age";
    static final String WORKER_QUEUE_CAPACITY = "async.worker.queue.capacity";

    private final MeterRegistry registry;
    private final Clock clock;

    public BacklogMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /*
     * 릴레이의 미발행 건수와 지금 넘길 수 있는 미발행 가운데 가장 이른 시각을 받는다. 그런 건이 없으면 시각은 null 이다.
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

    // 릴레이가 상한만큼 실패해 넘기기를 포기한 건수를 받는다. 데드레터처럼 0 보다 크면 사람이 보고 고친다
    public void abandoned(String name, LongSupplier abandoned) {
        Gauge.builder(RELAY_ABANDONED, abandoned::getAsLong)
                .description("아웃박스 릴레이가 넘기기를 포기한 건수")
                .tag("relay", name)
                .register(registry);
    }

    /*
     * 넘긴 순간에 그 건이 생긴 뒤 넘기기까지 걸린 시간을 남긴다. 어느 시각을 생긴 때로 볼지는 릴레이가 정한다. 재시도 끝에 넘긴 건도 든다.
     * 품질 목표(아웃박스 발행 지연 5초)는 이 p99 로 판정한다.
     * 5초를 경계로 두어 넘은 비율을 어림하지 않고 센다. 재시도 끝의 지연(백오프만 약 13.5분)까지 버킷에 담으려고 상한을 15분으로 둔다.
     */
    public void publishDelay(String name, Duration delay) {
        Timer.builder(RELAY_PUBLISH_DELAY)
                .description("아웃박스 릴레이의 발행 지연. 생긴 뒤 넘기기까지 걸린 시간")
                .tag("relay", name)
                .publishPercentileHistogram()
                .serviceLevelObjectives(PUBLISH_DELAY_TARGET)
                .maximumExpectedValue(PUBLISH_DELAY_MAX)
                .register(registry)
                .record(delay);
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

    /*
     * 대기열 용량도 함께 낸다(#1212). 대기열 길이 / 용량으로 포화를 본다.
     * 용량은 조정 값이라 바뀔 수 있어 대시보드와 규칙에 숫자로 적지 않고 이 지표에서 읽는다.
     */
    public void worker(String name, LongSupplier queueSize, Supplier<LocalDateTime> oldest, int capacity) {
        worker(name, queueSize, oldest);
        Gauge.builder(WORKER_QUEUE_CAPACITY, () -> capacity)
                .description("비동기 워커의 대기열 용량")
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
