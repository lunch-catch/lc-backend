package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BacklogMetricsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 12, 0, 0);

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final Clock clock = Clock.fixed(NOW.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
    private final BacklogMetrics metrics = new BacklogMetrics(registry, clock);

    @Test
    @DisplayName("릴레이의 미발행 건수와 가장 오래 기다린 건의 대기 시간을 공통 이름에 relay 꼬리표로 낸다")
    void 릴레이() {
        metrics.relay("adserving.impression", () -> 7, () -> NOW.minusSeconds(3));

        assertThat(registry.scrape())
                .contains("outbox_relay_backlog{relay=\"adserving.impression\"} 7.0")
                .contains("outbox_relay_oldest_age_seconds{relay=\"adserving.impression\"} 3.0");
    }

    @Test
    @DisplayName("릴레이가 포기한 건수를 공통 이름에 relay 꼬리표로 낸다")
    void 포기한_건수() {
        metrics.abandoned("adserving.impression", () -> 2);

        assertThat(registry.scrape()).contains("outbox_relay_abandoned{relay=\"adserving.impression\"} 2.0");
    }

    // 품질 목표 5초를 넘은 비율을 버킷 경계로 정확히 센다
    @Test
    @DisplayName("넘긴 순간의 발행 지연을 5초 경계가 있는 히스토그램으로 남긴다")
    void 발행_지연() {
        metrics.publishDelay("adserving.impression", java.time.Duration.ofSeconds(3));
        metrics.publishDelay("adserving.impression", java.time.Duration.ofSeconds(8));

        assertThat(registry.scrape())
                .contains("outbox_relay_publish_delay_seconds_bucket{relay=\"adserving.impression\",le=\"5.0\"} 1")
                .contains("outbox_relay_publish_delay_seconds_count{relay=\"adserving.impression\"} 2");
    }

    @Test
    @DisplayName("워커의 대기열 길이와 가장 오래 기다린 일의 대기 시간을 공통 이름에 worker 꼬리표로 낸다")
    void 워커() {
        metrics.worker("adserving.impression-write", () -> 40, () -> NOW.minusNanos(1_500_000_000));

        assertThat(registry.scrape())
                .contains("async_worker_queue_size{worker=\"adserving.impression-write\"} 40.0")
                .contains("async_worker_oldest_age_seconds{worker=\"adserving.impression-write\"} 1.5");
    }

    @Test
    @DisplayName("기다리는 것이 없으면 대기 시간은 0 이다")
    void 비었으면_0() {
        metrics.worker("adserving.serve-log-write", () -> 0, () -> null);

        assertThat(registry.scrape()).contains("async_worker_oldest_age_seconds{worker=\"adserving.serve-log-write\"} 0.0");
    }

    @Test
    @DisplayName("대기 시간은 긁을 때의 시각으로 센다. 값을 넘기는 쪽이 멈춰도 대기 시간은 계속 는다")
    void 긁을_때_센다() {
        AtomicReference<Clock> now = new AtomicReference<>(clock);
        BacklogMetrics moving = new BacklogMetrics(registry, new DelegatingClock(now));
        LocalDateTime oldest = NOW.minusSeconds(1);
        moving.relay("billing.payment-recheck", () -> 1, () -> oldest);

        now.set(Clock.offset(clock, java.time.Duration.ofSeconds(10)));

        assertThat(registry.scrape()).contains("outbox_relay_oldest_age_seconds{relay=\"billing.payment-recheck\"} 11.0");
    }

    private static final class DelegatingClock extends Clock {

        private final AtomicReference<Clock> delegate;

        DelegatingClock(AtomicReference<Clock> delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.time.ZoneId getZone() {
            return delegate.get().getZone();
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return delegate.get().withZone(zone);
        }

        @Override
        public java.time.Instant instant() {
            return delegate.get().instant();
        }
    }
}
