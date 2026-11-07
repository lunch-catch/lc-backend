package com.lunchcatch.global.logging;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextScheduledExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;

/*
 * 요청 밖에서 도는 스레드의 MDC 를 다루는 유일한 장치다. 쓰임은 둘이다.
 *
 * 새 문맥 열기(open)는 이어받을 문맥이 없는 실행이 쓴다. 스케줄 작업 한 번과 묶음 워커의 묶음 하나다.
 * 묶음 하나에 여러 요청이 섞여 있어 어느 요청의 traceId 도 이어받을 수 없다.
 *
 * 문맥 넘기기(wrap)는 요청 스레드가 실행기에 일을 넘길 때 쓴다. MDC 는 ThreadLocal 이라 다른 스레드로
 * 저절로 넘어가지 않는다. 넘기는 순간의 MDC 를 붙잡아 일이 돌 때 넣고 끝나면 되돌린다.
 * 가상 스레드 실행기에도 그대로 쓴다.
 *
 * 레지스트리는 MDC 접근자 하나만 둔 전용이다. 전역 레지스트리를 쓰면 클래스패스의 다른 접근자
 * (Micrometer Observation 등)까지 함께 붙잡아 넘겨, MDC 만 넘긴다는 이 장치의 뜻이 흐려진다.
 */
public final class LogContext {

    public static final String TRACE_ID = MdcLoggingFilter.TRACE_ID;
    public static final String JOB = "job";

    // clearMissing 이 없으면 넘긴 쪽이 비어 있을 때 재사용되는 스레드에 남은 앞선 일의 값을 그대로 쓴다
    private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder()
            .contextRegistry(new ContextRegistry().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor()))
            .clearMissing(true)
            .build();

    private LogContext() {
    }

    // 새 traceId 와 실행 이름만 든 문맥을 연다. 닫으면 열기 전의 MDC 로 돌아간다
    public static Scope open(String job) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.setContextMap(Map.of(TRACE_ID, newTraceId(), JOB, job));
        return () -> {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        };
    }

    public static Runnable wrap(Runnable task) {
        return SNAPSHOTS.captureAll().wrap(task);
    }

    public static ExecutorService wrap(ExecutorService executor) {
        return ContextExecutorService.wrap(executor, SNAPSHOTS);
    }

    public static ScheduledExecutorService wrap(ScheduledExecutorService executor) {
        return ContextScheduledExecutorService.wrap(executor, SNAPSHOTS);
    }

    static String newTraceId() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }

    // try-with-resources 로 닫는다. 닫는 일은 예외를 던지지 않는다
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
