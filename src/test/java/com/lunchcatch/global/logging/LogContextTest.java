package com.lunchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    class 새_문맥 {

        @Test
        void 새_traceId와_job을_넣는다() {
            try (LogContext.Scope ignored = LogContext.open("impression-writer")) {
                assertThat(MDC.get(LogContext.TRACE_ID)).isNotBlank();
                assertThat(MDC.get(LogContext.JOB)).isEqualTo("impression-writer");
            }
        }

        @Test
        void 열_때마다_traceId가_다르다() {
            String first;
            try (LogContext.Scope ignored = LogContext.open("job")) {
                first = MDC.get(LogContext.TRACE_ID);
            }
            try (LogContext.Scope ignored = LogContext.open("job")) {
                assertThat(MDC.get(LogContext.TRACE_ID)).isNotEqualTo(first);
            }
        }

        @Test
        void 열기_전의_값은_새_문맥에_섞이지_않는다() {
            MDC.put("uri", "/v1/feed");

            try (LogContext.Scope ignored = LogContext.open("job")) {
                assertThat(MDC.get("uri")).isNull();
            }
        }

        @Test
        void 닫으면_열기_전의_MDC로_돌아온다() {
            MDC.put(LogContext.TRACE_ID, "request-trace");
            MDC.put("uri", "/v1/feed");

            try (LogContext.Scope ignored = LogContext.open("job")) {
                assertThat(MDC.get(LogContext.TRACE_ID)).isNotEqualTo("request-trace");
            }

            assertThat(MDC.getCopyOfContextMap())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(LogContext.TRACE_ID, "request-trace", "uri", "/v1/feed"));
        }

        @Test
        void 열기_전에_비어_있었으면_닫은_뒤에도_비어_있다() {
            try (LogContext.Scope ignored = LogContext.open("job")) {
                assertThat(MDC.get(LogContext.JOB)).isEqualTo("job");
            }

            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }
    }

    @Nested
    class 문맥_넘기기 {

        @Test
        void 넘긴_일에는_넘긴_순간의_MDC가_있다() throws Exception {
            ExecutorService executor = LogContext.wrap(Executors.newSingleThreadExecutor());
            try {
                MDC.put(LogContext.TRACE_ID, "request-trace");

                String seen = executor.submit(() -> MDC.get(LogContext.TRACE_ID)).get(5, TimeUnit.SECONDS);

                assertThat(seen).isEqualTo("request-trace");
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        void 가상_스레드_실행기에도_넘긴_순간의_MDC가_넘어간다() throws Exception {
            ExecutorService executor = LogContext.wrap(Executors.newVirtualThreadPerTaskExecutor());
            try {
                MDC.put(LogContext.TRACE_ID, "request-trace");

                String seen = executor.submit(() -> MDC.get(LogContext.TRACE_ID)).get(5, TimeUnit.SECONDS);

                assertThat(seen).isEqualTo("request-trace");
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        void 예약_실행기에도_넘긴_순간의_MDC가_넘어간다() throws Exception {
            ScheduledExecutorService executor = LogContext.wrap(Executors.newSingleThreadScheduledExecutor());
            try {
                MDC.put(LogContext.TRACE_ID, "request-trace");

                String seen = executor.schedule(() -> MDC.get(LogContext.TRACE_ID), 1, TimeUnit.MILLISECONDS)
                        .get(5, TimeUnit.SECONDS);

                assertThat(seen).isEqualTo("request-trace");
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        void 넘긴_일이_끝나면_실행한_스레드의_MDC를_되돌린다() throws Exception {
            ExecutorService raw = Executors.newSingleThreadExecutor();
            ExecutorService executor = LogContext.wrap(raw);
            try {
                MDC.put(LogContext.TRACE_ID, "request-trace");
                executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

                Map<String, String> after = raw.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS);

                assertThat(after).isNullOrEmpty();
            } finally {
                executor.shutdownNow();
            }
        }

        // 재사용되는 스레드에 앞선 일의 값이 남아 있어도, 넘긴 쪽이 비어 있었으면 그 값을 쓰지 않는다
        @Test
        void 넘긴_순간의_MDC가_비어_있으면_실행한_스레드의_남은_값을_쓰지_않는다() throws Exception {
            ExecutorService raw = Executors.newSingleThreadExecutor();
            ExecutorService executor = LogContext.wrap(raw);
            try {
                raw.submit(() -> MDC.put(LogContext.TRACE_ID, "stale")).get(5, TimeUnit.SECONDS);

                String seen = executor.submit(() -> MDC.get(LogContext.TRACE_ID)).get(5, TimeUnit.SECONDS);

                assertThat(seen).isNull();
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        void 감싼_일_하나에도_넘긴_순간의_MDC가_넘어간다() throws Exception {
            MDC.put(LogContext.TRACE_ID, "request-trace");
            String[] seen = new String[1];
            Runnable task = LogContext.wrap(() -> seen[0] = MDC.get(LogContext.TRACE_ID));

            Thread thread = Thread.ofVirtual().start(task);
            thread.join(5000);

            assertThat(seen[0]).isEqualTo("request-trace");
        }
    }
}
