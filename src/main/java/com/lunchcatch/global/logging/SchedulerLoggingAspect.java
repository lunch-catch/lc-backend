package com.lunchcatch.global.logging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

/**
 * {@code @Scheduled} 메서드 실행마다 시작/종료(또는 실패)/소요시간을 자동으로 로깅하고,
 * 마지막으로 성공한 시각을 지표로 내보내는 공통 Aspect.
 * 스케줄러 클래스가 로깅 코드를 따로 안 짜도 되고, {@code @Scheduled}만 붙이면 이 Aspect가 자동으로
 * 적용된다 — "실행은 됐는데 처리할 게 없어서 로그를 아예 안 남기는" 개별 스케줄러가 있어도, 이
 * Aspect 덕분에 "그 시간에 스케줄러가 정상적으로 돌긴 한 건지"는 최소 1줄로 항상 확인할 수 있다.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class SchedulerLoggingAspect {

    private static final String LAST_SUCCESS = "batch.job.last.success.timestamp";
    private static final String DURATION = "batch.job.duration";
    private static final String RUNNING_SINCE = "batch.job.running.since";
    private static final Duration MAX_EXPECTED_DURATION = Duration.ofHours(1);

    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<String, AtomicLong> lastSuccessByJob = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> runningSinceByJob = new ConcurrentHashMap<>();

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object logScheduledExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String job = joinPoint.getSignature().toShortString();
        boolean quiet = isQuiet(joinPoint);
        Instant start = clock.instant();
        try (LogContext.Scope ignored = LogContext.open(job)) {
            logStep(quiet, "event=SCHEDULER_START job={}", job);
            AtomicLong lastSuccess = lastSuccessGauge(job, start);
            AtomicLong runningSince = runningSinceGauge(job);
            runningSince.set(start.getEpochSecond());

            try {
                Object result = joinPoint.proceed();
                lastSuccess.set(clock.instant().getEpochSecond());
                Duration took = Duration.between(start, clock.instant());
                recordDuration(job, "success", took);
                logStep(quiet, "event=SCHEDULER_END job={} durationMs={}", job, took.toMillis());
                return result;
            } catch (Throwable ex) {
                Duration took = Duration.between(start, clock.instant());
                recordDuration(job, "failure", took);
                log.error("event=SCHEDULER_FAILED job={} durationMs={}", job, took.toMillis(), ex);
                throw ex;
            } finally {
                runningSince.set(0);
            }
        }
    }

    // @QuietSchedulerLog 가 붙은 짧은 주기 작업은 정상 실행을 DEBUG 로 내린다. 실패는 언제나 ERROR 다
    private static boolean isQuiet(ProceedingJoinPoint joinPoint) {
        return joinPoint.getSignature() instanceof MethodSignature signature
                && AnnotationUtils.findAnnotation(signature.getMethod(), QuietSchedulerLog.class) != null;
    }

    private static void logStep(boolean quiet, String format, Object... args) {
        if (quiet) {
            log.debug(format, args);
        } else {
            log.info(format, args);
        }
    }

    /*
     * 실행 한 번에 걸린 시간이다. 트레이싱을 끈 회차에도 작업 시간을 보려고 지표로 남긴다.
     * 태그는 마지막 성공 게이지와 같은 scheduler 다. 여러 대의 값을 합쳐 분위수를 내도록 히스토그램을 켠다.
     * 상한은 1시간이다. 기본값 30초로는 00:00 묶음 같은 긴 작업이 가장 큰 버킷에 모여 p99 가 정확하지 않다.
     */
    private void recordDuration(String job, String outcome, Duration took) {
        Timer.builder(DURATION)
                .tag("scheduler", job)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .maximumExpectedValue(MAX_EXPECTED_DURATION)
                .description("Time taken by one scheduled run")
                .register(meterRegistry)
                .record(took);
    }

    /*
     * 도는 중인 실행이 시작된 시각이다. 돌지 않으면 0 이다(#1175).
     * 걸린 시간 타이머는 실행이 끝나야 기록되어, 멈춰 끝나지 않는 작업은 마감을 넘겨도 보이지 않는다.
     * 그래서 06 과 부하 시험 판정이 "지금 시각 - 시작 시각" 을 그 작업의 마감과 견준다.
     * 한 JVM 에서는 같은 작업이 겹쳐 돌지 않아 작업마다 값 하나로 충분하다. 서버가 여러 대면 instance 꼬리표로 갈린다.
     */
    private AtomicLong runningSinceGauge(String job) {
        return runningSinceByJob.computeIfAbsent(job, name -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder(RUNNING_SINCE, holder, AtomicLong::doubleValue)
                    .tag("scheduler", name)
                    .baseUnit("seconds")
                    .description("Unix time the running execution started, 0 when idle")
                    .register(meterRegistry);
            return holder;
        });
    }

    /*
     * 배치는 틀리게 도는 것보다 조용히 안 도는 것이 위험하다.
     * 에러 알람은 작업이 돌아야 울리므로 프로세스가 아예 안 뜨면 아무 신호도 없다.
     * 그래서 마지막 성공 시각을 내보내고 그 값이 낡는 것으로 감시한다.
     *
     * 첫 실행 시각으로 초기화하는 이유는 계속 실패하는 작업도 잡기 위해서다.
     * 성공할 때만 값이 갱신되므로, 실패만 반복하면 이 값이 그대로 낡아 알람이 울린다.
     * 0 으로 두면 기동 직후부터 낡은 것으로 보여 첫 실행 전에 울린다.
     */
    private AtomicLong lastSuccessGauge(String job, Instant firstSeen) {
        return lastSuccessByJob.computeIfAbsent(job, name -> {
            AtomicLong holder = new AtomicLong(firstSeen.getEpochSecond());
            Gauge.builder(LAST_SUCCESS, holder, AtomicLong::doubleValue)
                    /*
                     * job 이 아니라 scheduler 로 붙인다.
                     * Prometheus 의 job 은 스크랩 잡 이름으로 예약되어 있어, 같은 이름을 쓰면
                     * 수집 때 exported_job 으로 밀려나고 알람이 엉뚱한 값을 가리킨다.
                     */
                    .tag("scheduler", name)
                    .baseUnit("seconds")
                    .description("Unix time of the last successful run")
                    .register(meterRegistry);
            return holder;
        });
    }
}
