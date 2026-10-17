package com.launchcatch.analytics.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.event.EventRecord;
import com.launchcatch.analytics.event.EventType;
import com.launchcatch.analytics.event.EventWriteResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FunnelEventRecorderTest {

    private static final Executor CALLER = Runnable::run;

    @Mock
    private EventLogWriter writer;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static EventRecord record() {
        return new EventRecord(UUID.randomUUID(), EventType.IMPRESSION, Instant.parse("2026-10-17T00:30:15Z"),
                7L, 11L, 13L, UUID.randomUUID(), null, null, null);
    }

    private double count(String result) {
        return registry.counter("analytics_funnel_event_total", "result", result).count();
    }

    @Test
    void 기록에_성공하면_recorded로_센다() {
        when(writer.write(any(EventRecord.class))).thenReturn(new EventWriteResult(1, 0));

        new FunnelEventRecorder(writer, registry, CALLER).record(record());

        assertThat(count("recorded")).isEqualTo(1);
    }

    @Test
    void 이미_있는_이벤트면_duplicate로_센다() {
        when(writer.write(any(EventRecord.class))).thenReturn(new EventWriteResult(0, 1));

        new FunnelEventRecorder(writer, registry, CALLER).record(record());

        assertThat(count("duplicate")).isEqualTo(1);
        assertThat(count("recorded")).isZero();
    }

    @Test
    void 기록이_실패해도_예외를_밖으로_던지지_않고_failed로_센다() {
        when(writer.write(any(EventRecord.class))).thenThrow(new IllegalStateException("db down"));
        FunnelEventRecorder recorder = new FunnelEventRecorder(writer, registry, CALLER);

        assertThatCode(() -> recorder.record(record())).doesNotThrowAnyException();

        assertThat(count("failed")).isEqualTo(1);
    }

    @Test
    void 실행기가_거절하면_예외를_밖으로_던지지_않고_rejected로_센다() {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("queue full");
        };
        FunnelEventRecorder recorder = new FunnelEventRecorder(writer, registry, rejecting);

        assertThatCode(() -> recorder.record(record())).doesNotThrowAnyException();

        assertThat(count("rejected")).isEqualTo(1);
    }

    @Test
    void 큐가_차면_넘치는_이벤트를_버리고_발행한_쪽은_막히지_않는다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        when(writer.write(any(EventRecord.class))).thenAnswer(call -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new EventWriteResult(1, 0);
        });
        ThreadPoolExecutor bounded = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        FunnelEventRecorder recorder = new FunnelEventRecorder(writer, registry, bounded);

        recorder.record(record());
        started.await(5, TimeUnit.SECONDS);
        recorder.record(record());
        recorder.record(record());

        assertThat(count("rejected")).isEqualTo(1);
        release.countDown();
        bounded.shutdown();
        assertThat(bounded.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void 종료하면_실행기를_닫는다() {
        FunnelEventRecorder recorder = new FunnelEventRecorder(writer, registry);

        recorder.shutdown();
        recorder.record(record());

        assertThat(count("rejected")).isEqualTo(1);
    }
}
