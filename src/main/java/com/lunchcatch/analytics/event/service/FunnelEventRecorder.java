package com.lunchcatch.analytics.event.service;

import com.lunchcatch.analytics.event.EventRecord;
import com.lunchcatch.analytics.event.EventWriteResult;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/*
 * 퍼널 이벤트를 전용 실행기에서 event_log 에 기록한다. 호출한 쪽은 기록을 기다리지 않는다.
 *
 * 퍼널 기록은 원천 업무의 판정과 과금에 쓰지 않는다. 그래서 유실을 허용하고, 대신 분석의 어떤 실패도
 * 원천 업무로 번지지 않게 한다. 대기열이 가득 차면 새 이벤트를 버리고, 기록이 실패하면 예외를 던지지 않고
 * 경고 로그와 지표로만 남긴다. 돈과 맞아야 하는 건수는 원천 도메인의 집계 조회로 다시 센다.
 *
 * 실행기를 스프링 빈으로 노출하지 않는다. Executor 빈이 하나라도 있으면 부트가 기본 작업 실행기를
 * 만들지 않는다. 그래서 @Async 대신 이 서비스가 실행기에 직접 맡긴다.
 *
 * 대기열 크기와 스레드 수는 근거 없이 정한 예시값이다. 측정한 뒤 확정한다.
 */
@Slf4j
@Service
public class FunnelEventRecorder {

    static final String METRIC = "analytics_funnel_event_total";
    private static final int THREADS = 2;
    private static final int QUEUE_CAPACITY = 20_000;

    private final EventLogWriter writer;
    private final MeterRegistry registry;
    private final Executor executor;
    private final ThreadPoolExecutor ownedExecutor;

    @Autowired
    public FunnelEventRecorder(EventLogWriter writer, MeterRegistry registry) {
        this.writer = writer;
        this.registry = registry;
        this.ownedExecutor = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                daemonThreads(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor = ownedExecutor;
        registerCounters();
    }

    // 테스트가 기록을 호출 스레드에서 바로 돌리거나 대기열을 작게 줄이려고 쓴다.
    FunnelEventRecorder(EventLogWriter writer, MeterRegistry registry, Executor executor) {
        this.writer = writer;
        this.registry = registry;
        this.ownedExecutor = null;
        this.executor = executor;
        registerCounters();
    }

    // 첫 사건 전에도 시리즈가 있어야 increase() 가 첫 증가를 센다
    private void registerCounters() {
        for (String result : List.of("recorded", "duplicate", "rejected", "failed")) {
            registry.counter(METRIC, "result", result);
        }
    }

    public void record(EventRecord record) {
        try {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            executor.execute(() -> writeWithMdc(record, mdc));
        } catch (RejectedExecutionException e) {
            count("rejected");
        }
    }

    /*
     * 기록은 전용 실행기의 스레드에서 돌아 요청의 MDC 가 없다. 부른 쪽의 MDC 를 복사해 넘기고, 돌기 전에 이
     * 스레드에 있던 MDC 는 끝난 뒤 되돌린다. 테스트처럼 호출 스레드에서 바로 돌 때 요청의 MDC 를 지우지 않으려는 것이다.
     */
    private void writeWithMdc(EventRecord record, Map<String, String> mdc) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (mdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(mdc);
            }
            write(record);
        } finally {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    private void write(EventRecord record) {
        try {
            EventWriteResult result = writer.write(record);
            count(result.duplicated() > 0 ? "duplicate" : "recorded");
        } catch (RuntimeException e) {
            count("failed");
            log.warn("event=ANALYTICS_FUNNEL_EVENT_RECORD_FAILED eventType={} exceptionType={} fallback=DROP_EVENT",
                    record.eventType(), e.getClass().getSimpleName());
        }
    }

    private void count(String result) {
        registry.counter(METRIC, "result", result).increment();
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    /*
     * 데몬으로 만들지 않으면 이 풀의 스레드가 JVM 종료를 붙잡는다.
     * 패키지 범위로 두어 테스트가 그 성질을 직접 본다. 위의 Executor 를 받는 생성자와 같은 이유다.
     */
    static ThreadFactory daemonThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "funnel-event-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
