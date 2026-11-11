package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ThreadKindMetricsTest {

    @Test
    @DisplayName("스레드 이름 끝에 붙은 숫자 묶음만 지워 종류로 묶는다")
    void 끝의_숫자만_지운다() {
        assertThat(ThreadKindMetrics.kindOf("http-nio-8080-exec-12")).isEqualTo("http-nio-8080-exec");
        assertThat(ThreadKindMetrics.kindOf("http-nio-8081-exec-3")).isEqualTo("http-nio-8081-exec");
        assertThat(ThreadKindMetrics.kindOf("lettuce-nioEventLoop-4-1")).isEqualTo("lettuce-nioEventLoop");
        assertThat(ThreadKindMetrics.kindOf("Thread-37")).isEqualTo("Thread");
        assertThat(ThreadKindMetrics.kindOf("HikariPool-1 housekeeper")).isEqualTo("HikariPool-1 housekeeper");
        assertThat(ThreadKindMetrics.kindOf("impression-writer")).isEqualTo("impression-writer");
    }

    @Test
    @DisplayName("종류가 상한을 넘으면 그 뒤에 처음 보는 종류는 other 로 센다")
    void 상한을_넘으면_other_로_센다() {
        ThreadKindMetrics metrics = new ThreadKindMetrics();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < ThreadKindMetrics.MAX_KINDS + 5; i++) {
            names.add("worker" + (char) ('a' + i % 26) + (char) ('a' + i / 26) + "-1");
        }
        names.add("workeraa-2");

        Map<String, Integer> counts = metrics.count(names);

        assertThat(counts).hasSize(ThreadKindMetrics.MAX_KINDS + 1);
        assertThat(counts.get(ThreadKindMetrics.OTHER)).isEqualTo(5);
        assertThat(counts.get("workeraa")).isEqualTo(2);
    }

    @Test
    @DisplayName("한 번 센 종류는 다음에 세어도 other 로 밀리지 않는다")
    void 아는_종류는_계속_제_이름으로_센다() {
        ThreadKindMetrics metrics = new ThreadKindMetrics();
        metrics.count(List.of("first-1"));

        assertThat(metrics.count(List.of("first-2", "first-3"))).containsEntry("first", 2);
    }

    @Test
    @DisplayName("등록하면 살아 있는 스레드가 종류별 게이지로 나온다")
    void 종류별_게이지로_나온다() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch hold = new CountDownLatch(1);
        Thread worker = Thread.ofPlatform().name("kind-test-worker-7").start(() -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (ThreadKindMetrics metrics = new ThreadKindMetrics()) {
            metrics.bindTo(registry);

            Gauge gauge = registry.find(ThreadKindMetrics.METRIC).tag("kind", "kind-test-worker").gauge();
            assertThat(gauge).isNotNull();
            assertThat(gauge.value()).isEqualTo(1.0);
        } finally {
            hold.countDown();
            worker.join();
        }
    }

    @Test
    @DisplayName("사라진 종류의 시계열은 다음 세기에서 지운다")
    void 사라진_종류는_지운다() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch hold = new CountDownLatch(1);
        Thread worker = Thread.ofPlatform().name("kind-gone-worker-1").start(() -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (ThreadKindMetrics metrics = new ThreadKindMetrics()) {
            metrics.bindTo(registry);
            hold.countDown();
            worker.join();

            metrics.refresh();

            assertThat(registry.find(ThreadKindMetrics.METRIC).tag("kind", "kind-gone-worker").gauge()).isNull();
        } finally {
            hold.countDown();
            worker.join();
        }
    }

    @Test
    @DisplayName("같은 종류의 스레드 수가 바뀌면 다음 세기에서 게이지 값이 따라 바뀐다")
    void 스레드_수가_바뀌면_값이_바뀐다() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch hold = new CountDownLatch(1);
        Runnable waiting = () -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread first = Thread.ofPlatform().name("kind-grow-worker-1").start(waiting);
        Thread second = null;
        try (ThreadKindMetrics metrics = new ThreadKindMetrics()) {
            metrics.bindTo(registry);
            second = Thread.ofPlatform().name("kind-grow-worker-2").start(waiting);

            metrics.refresh();

            assertThat(registry.find(ThreadKindMetrics.METRIC).tag("kind", "kind-grow-worker").gauge().value()).isEqualTo(2.0);
        } finally {
            hold.countDown();
            first.join();
            if (second != null) {
                second.join();
            }
        }
    }

    /*
     * 다시 세는 동안 Prometheus 가 긁어도 같은 꼬리표가 둘 보이면 안 된다(#1219).
     * 보이면 그 수집이 통째로 실패해 그 서버의 지표 전체가 한 칸 빈다.
     */
    @Test
    @DisplayName("다시 세는 동안 긁어도 수집이 실패하지 않는다")
    void 다시_세는_동안_긁어도_수집이_실패하지_않는다() throws InterruptedException {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger refreshes = new AtomicInteger();
        AtomicReference<Throwable> refreshError = new AtomicReference<>();
        List<String> failures = new ArrayList<>();
        int refreshesWhileScraping;
        try (ThreadKindMetrics metrics = new ThreadKindMetrics()) {
            metrics.bindTo(registry);
            Thread refresher = Thread.ofPlatform().name("kind-race-refresher").start(() -> {
                started.countDown();
                try {
                    while (!stop.get()) {
                        metrics.refresh();
                        refreshes.incrementAndGet();
                    }
                } catch (Throwable e) {
                    refreshError.set(e);
                }
            });
            try {
                started.await();
                int before = refreshes.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                /*
                 * 수집과 겹친 다시 세기가 충분히 쌓일 때까지 긁는다.
                 * 다시 세기가 늦게 시작해도 정상 코드가 실패하지 않는다.
                 */
                for (int i = 0; (i < 20_000 || refreshes.get() - before < 100)
                        && failures.isEmpty() && System.nanoTime() < deadline; i++) {
                    try {
                        registry.scrape();
                    } catch (RuntimeException e) {
                        failures.add(e.getMessage());
                    }
                }
                refreshesWhileScraping = refreshes.get() - before;
            } finally {
                stop.set(true);
                refresher.join();
            }
        }

        assertThat(refreshError.get()).isNull();
        // 다시 세기가 수집과 겹쳐 돌지 않았으면 이 테스트는 아무것도 보지 않은 것이다
        assertThat(refreshesWhileScraping).isPositive();
        assertThat(failures).isEmpty();
    }
}
