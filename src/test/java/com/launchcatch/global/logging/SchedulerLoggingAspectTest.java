package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.scheduling.annotation.Scheduled;

class SchedulerLoggingAspectTest {

    private static final String LAST_SUCCESS = "batch.job.last.success.timestamp";

    public static class FakeJob {

        @Scheduled(fixedDelay = 1000)
        public void succeed() {
        }

        @Scheduled(fixedDelay = 1000)
        public void fail() {
            throw new IllegalStateException("boom");
        }

        public void unscheduled() {
        }
    }

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private LogCapture logs;
    private FakeJob job;

    @BeforeEach
    void setUp() {
        logs = LogCapture.attach(SchedulerLoggingAspect.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new FakeJob());
        factory.addAspect(new SchedulerLoggingAspect(registry));
        job = factory.getProxy();
    }

    @AfterEach
    void detachLogs() {
        logs.close();
    }

    private List<String> messages() {
        return logs.events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void 성공하면_시작과_종료를_INFO로_남긴다() {
        // when
        job.succeed();

        // then
        assertThat(logs.events()).extracting(ILoggingEvent::getLevel).containsExactly(Level.INFO, Level.INFO);
        assertThat(messages().get(0)).startsWith("event=SCHEDULER_START job=").contains("succeed()");
        assertThat(messages().get(1)).startsWith("event=SCHEDULER_END job=").contains("succeed()").contains("durationMs=");
    }

    @Test
    void 실패하면_시작과_실패를_남기고_예외를_그대로_다시_던진다() {
        // when
        assertThatThrownBy(() -> job.fail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        // then
        assertThat(messages().get(0)).startsWith("event=SCHEDULER_START");
        assertThat(messages().get(1)).startsWith("event=SCHEDULER_FAILED job=").contains("fail()");
    }

    @Test
    void 실패_로그에는_스택트레이스가_함께_남는다() {
        // when
        assertThatThrownBy(() -> job.fail()).isInstanceOf(IllegalStateException.class);

        // then
        ILoggingEvent failed = logs.events().get(1);
        assertThat(failed.getLevel()).isEqualTo(Level.ERROR);
        assertThat(failed.getThrowableProxy()).isNotNull();
        assertThat(failed.getThrowableProxy().getMessage()).isEqualTo("boom");
    }

    @Test
    void Scheduled가_없는_메서드는_로그를_남기지_않는다() {
        // when
        job.unscheduled();

        // then
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void 성공하면_마지막_성공_시각_게이지가_현재_시각으로_갱신된다() {
        // given
        long before = Instant.now().getEpochSecond();

        // when
        job.succeed();

        // then
        long after = Instant.now().getEpochSecond();
        Gauge gauge = registry.get(LAST_SUCCESS).gauge();
        assertThat(gauge.getId().getTag("scheduler")).contains("succeed()");
        assertThat(gauge.value()).isBetween((double) before, (double) after);
    }

    @Test
    void 실패만_해도_게이지는_0이_아니라_첫_실행_시각으로_시작한다() {
        // given
        long before = Instant.now().getEpochSecond();

        // when
        assertThatThrownBy(() -> job.fail()).isInstanceOf(IllegalStateException.class);

        // then
        long after = Instant.now().getEpochSecond();
        Gauge gauge = registry.get(LAST_SUCCESS).gauge();
        assertThat(gauge.value()).isBetween((double) before, (double) after);
    }

    @Test
    void 작업마다_게이지를_따로_둔다() {
        // when
        job.succeed();
        assertThatThrownBy(() -> job.fail()).isInstanceOf(IllegalStateException.class);

        // then
        assertThat(registry.find(LAST_SUCCESS).gauges()).hasSize(2);
    }
}
