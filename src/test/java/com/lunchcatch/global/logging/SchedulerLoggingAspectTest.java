package com.lunchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.lunchcatch.global.config.SchedulingConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.TaskUtils;

class SchedulerLoggingAspectTest {

    private static final String LAST_SUCCESS = "batch.job.last.success.timestamp";
    private static final String DURATION = "batch.job.duration";
    private static final String RUNNING_SINCE = "batch.job.running.since";
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

        @QuietSchedulerLog
        @Scheduled(fixedDelay = 1000)
        public void quiet() {
            if (failure != null) {
                throw failure;
            }
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
    void 조용한_작업은_시작과_종료를_DEBUG로_남긴다() {
        // given
        logs.level(Level.DEBUG);

        // when
        job.quiet();

        // then
        assertThat(logs.events()).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG, Level.DEBUG);
        assertThat(messages().get(0)).startsWith("event=SCHEDULER_START job=").contains("quiet()");
    }

    @Test
    void 조용한_작업도_실패는_ERROR로_남긴다() {
        // given
        target.failure = new IllegalStateException("boom");

        // when
        assertThatThrownBy(() -> job.quiet()).isInstanceOf(IllegalStateException.class);

        // then
        assertThat(logs.events()).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void Scheduled가_없는_메서드는_로그를_남기지_않는다() {
        // when
        job.unscheduled();

        // then
        assertThat(logs.events()).isEmpty();
    }

    // --- MDC -------------------------------------------------------------------

    @Test
    void 실행_중에는_새_traceId와_작업_이름이_MDC에_있다() {
        // given
        String[] seen = new String[2];
        target.work = () -> {
            seen[0] = MDC.get(LogContext.TRACE_ID);
            seen[1] = MDC.get(LogContext.JOB);
        };

        // when
        job.run();

        // then
        assertThat(seen[0]).isNotBlank();
        assertThat(seen[1]).contains("run()");
    }

    @Test
    void 시작과_종료_로그에도_같은_traceId가_붙는다() {
        // when
        job.run();

        // then
        assertThat(logs.events()).extracting(event -> event.getMDCPropertyMap().get(LogContext.TRACE_ID))
                .doesNotContainNull()
                .containsOnly(logs.events().get(0).getMDCPropertyMap().get(LogContext.TRACE_ID));
    }

    @Test
    void 실패해도_실패_로그에_traceId가_붙고_끝나면_MDC를_비운다() {
        // when
        failOnce();

        // then
        assertThat(logs.events().get(1).getMDCPropertyMap()).containsKey(LogContext.TRACE_ID);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void 실행마다_traceId가_다르다() {
        // when
        job.run();
        job.run();

        // then
        assertThat(logs.events().get(0).getMDCPropertyMap().get(LogContext.TRACE_ID))
                .isNotEqualTo(logs.events().get(2).getMDCPropertyMap().get(LogContext.TRACE_ID));
    }

    // --- 실행 시간 타이머 -----------------------------------------------------------

    private Timer duration(String outcome) {
        return registry.find(DURATION).tag("outcome", outcome).timers().stream()
                .filter(timer -> timer.getId().getTag("scheduler").contains("run()"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 성공한_실행의_걸린_시간을_success_로_기록한다() {
        // given
        target.work = () -> clock.advanceSeconds(3);

        // when
        job.run();

        // then
        assertThat(duration("success").count()).isEqualTo(1);
        assertThat(duration("success").totalTime(TimeUnit.SECONDS)).isEqualTo(3.0);
    }

    @Test
    void 실패한_실행의_걸린_시간을_failure_로_기록한다() {
        // given
        target.work = () -> clock.advanceSeconds(2);

        // when
        failOnce();

        // then
        assertThat(duration("failure").count()).isEqualTo(1);
        assertThat(duration("failure").totalTime(TimeUnit.SECONDS)).isEqualTo(2.0);
        assertThat(registry.find(DURATION).tag("outcome", "success").timers()).isEmpty();
    }

    // 메모리 레지스트리는 히스토그램 버킷을 내보내지 않아 실제로 쓰는 Prometheus 레지스트리로 본다
    @Test
    void 실행_시간은_분위수를_합칠_수_있게_히스토그램을_낸다() {
        // given
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AspectJProxyFactory factory = new AspectJProxyFactory(new FakeJob());
        factory.addAspect(new SchedulerLoggingAspect(prometheus, clock));
        FakeJob prometheusJob = factory.getProxy();

        // when
        prometheusJob.run();

        // then
        assertThat(prometheus.scrape()).contains("batch_job_duration_seconds_bucket{");
    }

    // 기본 상한 30초를 넘는 작업도 버킷에 담겨야 p99 가 실제 시간에 가깝다
    @Test
    void 실행_시간_히스토그램은_30초를_넘는_작업도_버킷에_담는다() {
        // given
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AspectJProxyFactory factory = new AspectJProxyFactory(new FakeJob());
        factory.addAspect(new SchedulerLoggingAspect(prometheus, clock));
        FakeJob prometheusJob = factory.getProxy();

        // when
        prometheusJob.run();

        // then
        assertThat(prometheus.scrape()).containsPattern("batch_job_duration_seconds_bucket\\{[^}]*le=\"(1[0-9]{3}|[2-9][0-9]{3})\\.");
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

    // --- 도는 중의 시작 시각 게이지 -------------------------------------------------------

    private double runningSince() {
        return registry.find(RUNNING_SINCE).gauges().stream()
                .filter(gauge -> gauge.getId().getTag("scheduler").contains("run()"))
                .findFirst()
                .orElseThrow()
                .value();
    }

    @Test
    void 도는_동안에는_시작_시각을_게이지로_낸다() {
        // given
        double[] seen = new double[1];
        target.work = () -> {
            clock.advanceSeconds(30);
            seen[0] = runningSince();
        };

        // when
        job.run();

        // then
        assertThat(seen[0]).isEqualTo(START.getEpochSecond());
    }

    @Test
    void 성공하고_나면_시작_시각을_0으로_되돌린다() {
        // when
        job.run();

        // then
        assertThat(runningSince()).isZero();
    }

    @Test
    void 실패해도_시작_시각을_0으로_되돌린다() {
        // when
        failOnce();

        // then
        assertThat(runningSince()).isZero();
    }

    @Test
    void 다음_실행은_그_실행의_시작_시각을_낸다() {
        // given
        job.run();
        clock.advanceSeconds(60);
        double[] seen = new double[1];
        target.work = () -> seen[0] = runningSince();

        // when
        job.run();

        // then
        assertThat(seen[0]).isEqualTo(START.getEpochSecond() + 60);
    }

    @Test
    void 시작_시각_게이지는_작업마다_scheduler_태그로_따로_둔다() {
        // when
        job.run();
        job.other();

        // then
        assertThat(registry.find(RUNNING_SINCE).gauges())
                .extracting(gauge -> gauge.getId().getTag("scheduler"))
                .hasSize(2)
                .anyMatch(name -> name.contains("run()"))
                .anyMatch(name -> name.contains("other()"));
    }

    // 06 과 부하 시험 판정이 이 이름으로 묻는다
    @Test
    void 시작_시각_게이지는_Prometheus_에서_batch_job_running_since_seconds_로_보인다() {
        // given
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AspectJProxyFactory factory = new AspectJProxyFactory(new FakeJob());
        factory.addAspect(new SchedulerLoggingAspect(prometheus, clock));
        FakeJob prometheusJob = factory.getProxy();

        // when
        prometheusJob.run();

        // then
        assertThat(prometheus.scrape()).contains("batch_job_running_since_seconds{");
    }

    @Test
    void 반복_작업이_실패하면_애스펙트만_스택을_남기고_스케줄러의_오류_처리기는_다시_남기지_않는다() {
        // given
        target.failure = new IllegalStateException("boom");

        try (LogCapture springLogs = LogCapture.attach(
                "org.springframework.scheduling.support.TaskUtils$LoggingErrorHandler")) {
            // when
            TaskUtils.decorateTaskWithErrorHandler(job::run, SchedulingConfig.ALREADY_LOGGED, true).run();

            // then
            ILoggingEvent aspectFailure = logs.events().get(1);
            assertThat(aspectFailure.getFormattedMessage()).startsWith("event=SCHEDULER_FAILED");
            assertThat(aspectFailure.getThrowableProxy()).isNotNull();
            assertThat(aspectFailure.getMDCPropertyMap()).containsKey(LogContext.TRACE_ID);
            assertThat(springLogs.events()).isEmpty();
        }
    }
}
