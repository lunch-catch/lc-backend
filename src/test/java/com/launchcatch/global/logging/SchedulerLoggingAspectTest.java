package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.scheduling.annotation.Scheduled;

class SchedulerLoggingAspectTest {

    private static final String LAST_SUCCESS = "batch.job.last.success.timestamp";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");

    /*
     * 게이지는 초 단위라서 실제 시계로는 초기값과 갱신값이 같은 초에 들어와 구분되지 않는다.
     * 시각을 테스트가 직접 앞으로 돌려 두 값을 다르게 만든다.
     */
    private static final class MutableClock extends Clock {

        private Instant now = START;

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public static class FakeJob {

        Runnable work = () -> {
        };
        RuntimeException failure;

        @Scheduled(fixedDelay = 1000)
        public void run() {
            work.run();
            if (failure != null) {
                throw failure;
            }
        }

        @Scheduled(fixedDelay = 1000)
        public void other() {
        }

        public void unscheduled() {
        }
    }

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock();
    private LogCapture logs;
    private FakeJob target;
    private FakeJob job;

    @BeforeEach
    void setUp() {
        logs = LogCapture.attach(SchedulerLoggingAspect.class);
        target = new FakeJob();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new SchedulerLoggingAspect(registry, clock));
        job = factory.getProxy();
    }

    @AfterEach
    void detachLogs() {
        logs.close();
    }

    private List<String> messages() {
        return logs.events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private double lastSuccess() {
        return registry.find(LAST_SUCCESS).gauges().stream()
                .filter(gauge -> gauge.getId().getTag("scheduler").contains("run()"))
                .findFirst()
                .orElseThrow()
                .value();
    }

    private void failOnce() {
        target.failure = new IllegalStateException("boom");
        assertThatThrownBy(() -> job.run()).isInstanceOf(IllegalStateException.class);
    }

    // --- 로그 -------------------------------------------------------------------

    @Test
    void 성공하면_시작과_종료를_INFO로_남긴다() {
        // when
        job.run();

        // then
        assertThat(logs.events()).extracting(ILoggingEvent::getLevel).containsExactly(Level.INFO, Level.INFO);
        assertThat(messages().get(0)).startsWith("event=SCHEDULER_START job=").contains("run()");
        assertThat(messages().get(1)).startsWith("event=SCHEDULER_END job=").contains("run()").contains("durationMs=");
    }

    @Test
    void 실패하면_시작과_실패를_남기고_예외를_그대로_다시_던진다() {
        // given
        IllegalStateException failure = new IllegalStateException("boom");
        target.failure = failure;

        // when
        assertThatThrownBy(() -> job.run()).isSameAs(failure);

        // then
        assertThat(messages().get(0)).startsWith("event=SCHEDULER_START");
        assertThat(messages().get(1)).startsWith("event=SCHEDULER_FAILED job=").contains("run()");
    }

    @Test
    void 실패_로그에는_스택트레이스가_함께_남는다() {
        // when
        failOnce();

        // then
        ILoggingEvent failed = logs.events().get(1);
        assertThat(failed.getLevel()).isEqualTo(Level.ERROR);
        assertThat(failed.getThrowableProxy()).isNotNull();
        assertThat(failed.getThrowableProxy().getMessage()).isEqualTo("boom");
    }

    @Test
    void 소요시간은_시계가_흐른_만큼_밀리초로_남긴다() {
        // given
        target.work = () -> clock.advanceSeconds(3);

        // when
        job.run();

        // then
        assertThat(messages().get(1)).contains("durationMs=3000");
    }

    @Test
    void Scheduled가_없는_메서드는_로그를_남기지_않는다() {
        // when
        job.unscheduled();

        // then
        assertThat(logs.events()).isEmpty();
    }

    // --- 마지막 성공 시각 게이지 ---------------------------------------------------------

    @Test
    void 성공하면_마지막_성공_시각이_성공한_시각으로_갱신된다() {
        // given
        failOnce();
        target.failure = null;
        clock.advanceSeconds(60);

        // when
        job.run();

        // then
        assertThat(lastSuccess()).isEqualTo(START.getEpochSecond() + 60);
    }

    @Test
    void 실패하면_마지막_성공_시각은_갱신되지_않는다() {
        // given
        job.run();
        clock.advanceSeconds(60);

        // when
        failOnce();

        // then
        assertThat(lastSuccess()).isEqualTo(START.getEpochSecond());
    }

    @Test
    void 실패만_반복하면_게이지는_0이_아니라_첫_실행_시각에_머문다() {
        // given
        failOnce();
        clock.advanceSeconds(60);

        // when
        failOnce();

        // then
        assertThat(lastSuccess()).isEqualTo(START.getEpochSecond());
    }

    @Test
    void 게이지에는_작업_이름이_scheduler_태그로_붙는다() {
        // when
        job.run();

        // then
        Gauge gauge = registry.get(LAST_SUCCESS).gauge();
        assertThat(gauge.getId().getTag("scheduler")).contains("run()");
    }

    @Test
    void 작업마다_게이지를_따로_둔다() {
        // when
        job.run();
        job.other();

        // then
        assertThat(registry.find(LAST_SUCCESS).gauges()).hasSize(2);
    }
}
