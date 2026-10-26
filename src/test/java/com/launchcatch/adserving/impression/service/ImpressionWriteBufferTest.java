package com.launchcatch.adserving.impression.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.contract.ImpressionServedEvent;
import com.launchcatch.adserving.engine.SlotLabels;
import com.launchcatch.adserving.feed.repository.ImpressionCountRepository;
import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionLogId;
import com.launchcatch.adserving.impression.repository.ImpressionLogInsert.Row;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("노출 저장 큐")
class ImpressionWriteBufferTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 10, 27, 12, 0, 0);

    private final ImpressionLogRepository impressions = mock(ImpressionLogRepository.class);
    private final ImpressionCountRepository counts = mock(ImpressionCountRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ImpressionWriteBuffer buffer = new ImpressionWriteBuffer(impressions, counts, publisher, transactions,
            meterRegistry);

    @AfterEach
    void stop() {
        if (buffer.isRunning()) {
            buffer.stop();
        }
    }

    private static QueuedImpression valid(int n, boolean mismatch) {
        return new QueuedImpression(ImpressionLog.valid(new UUID(0, n), 3_000L + n, TODAY,
                new ImpressionLog.Placement(101L, 55L, SlotLabels.ALLOCATION, 1),
                new ImpressionLog.Sighting(RECEIVED_AT.minusSeconds(1), 37.5, 127.0, null), false), RECEIVED_AT, mismatch);
    }

    private static QueuedImpression invalid(int n) {
        return new QueuedImpression(ImpressionLog.unknown(new UUID(0, n), 3_000L + n, TODAY, 101L,
                new ImpressionLog.Sighting(RECEIVED_AT.minusSeconds(1), 37.5, 127.0, null)), RECEIVED_AT, false);
    }

    // 넘긴 행을 모두 새로 넣은 것으로 돌려준다
    private void insertAll() {
        when(impressions.insertAllIgnore(anyList())).thenAnswer(invocation -> {
            List<Row> rows = invocation.getArgument(0);
            return rows.stream().map(row -> row.log().getId()).collect(Collectors.toSet());
        });
    }

    @Nested
    @DisplayName("넣기")
    class Offer {

        @Test
        void 넣은_노출은_비울_때_한_트랜잭션으로_묶어_저장한다() {
            insertAll();
            QueuedImpression first = valid(1, false);
            QueuedImpression second = invalid(2);
            buffer.offer(first);
            buffer.offer(second);

            buffer.flush();

            verify(impressions).insertAllIgnore(List.of(new Row(first.log(), RECEIVED_AT), new Row(second.log(), RECEIVED_AT)));
            assertThat(transactions.executed).isEqualTo(1);
        }

        @Test
        void 큐가_차면_100ms_기다린_뒤_버리고_센다() {
            IntStream.range(0, ImpressionWriteBuffer.CAPACITY).forEach(i -> buffer.offer(invalid(i)));

            long started = System.nanoTime();
            buffer.offer(invalid(ImpressionWriteBuffer.CAPACITY));
            long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(waitedMs).isGreaterThanOrEqualTo(90);
            assertThat(meterRegistry.counter(ImpressionWriteBuffer.DROPPED_METRIC).count()).isEqualTo(1);
            assertThat(meterRegistry.get(ImpressionWriteBuffer.QUEUE_METRIC).gauge().value())
                    .isEqualTo(ImpressionWriteBuffer.CAPACITY);
        }

        @Test
        void 비울_때_500건씩_나눠_저장한다() {
            insertAll();
            IntStream.range(0, ImpressionWriteBuffer.BATCH_SIZE + 1).forEach(i -> buffer.offer(invalid(i)));

            buffer.flush();

            verify(impressions, times(2)).insertAllIgnore(anyList());
        }
    }

    @Nested
    @DisplayName("저장 뒤")
    class AfterWrite {

        @Test
        void 새로_들어간_유효_노출만_트랜잭션_안에서_이벤트를_내고_커밋_뒤_카운터를_올린다() {
            QueuedImpression fresh = valid(1, false);
            QueuedImpression stored = valid(2, false);
            when(impressions.insertAllIgnore(anyList())).thenReturn(Set.of(fresh.log().getId(), invalid(3).log().getId()));
            AtomicBoolean publishedInTransaction = new AtomicBoolean();
            doAnswer(invocation -> {
                publishedInTransaction.set(transactions.inside);
                return null;
            }).when(publisher).publishEvent(any(Object.class));
            buffer.offer(fresh);
            buffer.offer(stored);
            buffer.offer(invalid(3));

            buffer.flush();

            ImpressionLog log = fresh.log();
            verify(publisher).publishEvent(new ImpressionServedEvent("impression:" + log.getId().getServeId(),
                    log.getDisplayedAt(), log.getId().getMemberId(), 101L, 55L, log.getId().getServeId()));
            assertThat(publishedInTransaction).isTrue();
            verify(counts).increment(TODAY, 101L);
        }

        // 저장되기 전에 다시 보낸 노출이 같은 묶음에 둘 들어오면 저장소는 처음 것만 넣는다
        @Test
        void 같은_묶음에_같은_노출이_둘이면_한_번만_이벤트를_내고_센다() {
            QueuedImpression first = valid(1, true);
            QueuedImpression again = valid(1, true);
            when(impressions.insertAllIgnore(anyList())).thenReturn(Set.of(first.log().getId()));
            buffer.offer(first);
            buffer.offer(again);

            buffer.flush();

            verify(publisher, times(1)).publishEvent(any(Object.class));
            verify(counts, times(1)).increment(TODAY, 101L);
            assertThat(meterRegistry.counter(ImpressionWriteBuffer.CAMPAIGN_MISMATCH_METRIC).count()).isEqualTo(1);
        }

        // 처음 저장된 노출이 커밋된 뒤에만 센다. 다시 온 요청은 세지 않는다(#783)
        @Test
        void 캠페인이_달랐던_노출은_새로_들어갔을_때만_센다() {
            QueuedImpression fresh = valid(1, true);
            QueuedImpression stored = valid(2, true);
            when(impressions.insertAllIgnore(anyList())).thenReturn(Set.of(fresh.log().getId()));
            buffer.offer(fresh);
            buffer.offer(stored);

            buffer.flush();

            assertThat(meterRegistry.counter(ImpressionWriteBuffer.CAMPAIGN_MISMATCH_METRIC).count()).isEqualTo(1);
        }

        @Test
        void 저장이_실패하면_이벤트도_카운터도_내지_않고_서버가_내려가며_쓰지_못한_행으로_센다() {
            when(impressions.insertAllIgnore(anyList())).thenThrow(new QueryTimeoutException("DB 가 멈췄다"));
            buffer.offer(valid(1, false));
            buffer.offer(valid(2, false));

            buffer.flush();

            verifyNoInteractions(publisher, counts);
            assertThat(meterRegistry.counter(ImpressionWriteBuffer.WRITE_FAILED_METRIC).count()).isEqualTo(1);
            assertThat(meterRegistry.counter(ImpressionWriteBuffer.LOST_METRIC).count()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("작업자")
    class Worker {

        @Test
        void 실패한_묶음을_같은_묶음으로_다시_쓴다() throws InterruptedException {
            QueuedImpression impression = valid(1, false);
            Set<ImpressionLogId> ids = Set.of(impression.log().getId());
            when(impressions.insertAllIgnore(anyList()))
                    .thenThrow(new QueryTimeoutException("DB 가 멈췄다"))
                    .thenReturn(ids);
            buffer.start();
            buffer.offer(impression);

            waitUntil(() -> meterRegistry.counter(ImpressionWriteBuffer.WRITE_FAILED_METRIC).count() == 1
                    && mockingDetailsCalls() == 2);

            verify(impressions, times(2)).insertAllIgnore(List.of(new Row(impression.log(), RECEIVED_AT)));
            verify(counts).increment(TODAY, 101L);
        }

        // 늘 실패하는 묶음이 작업자를 붙잡아 다른 노출까지 막지 않게 한다(REL-2-07)
        @Test
        void 상한까지_실패한_묶음은_버리고_세고_다음_묶음을_쓴다() throws InterruptedException {
            buffer.pause = millis -> {
            };
            QueuedImpression poison = valid(1, false);
            QueuedImpression next = valid(2, false);
            when(impressions.insertAllIgnore(anyList())).thenAnswer(invocation -> {
                List<Row> rows = invocation.getArgument(0);
                if (rows.getFirst().log() == poison.log()) {
                    throw new IllegalStateException("늘 실패하는 행");
                }
                return Set.of(next.log().getId());
            });
            buffer.offer(poison);
            buffer.start();

            waitUntil(() -> meterRegistry.counter(ImpressionWriteBuffer.LOST_METRIC).count() == 1);
            buffer.offer(next);
            waitUntil(() -> mockingDetailsCalls() == ImpressionWriteBuffer.MAX_ATTEMPTS + 2);

            assertThat(meterRegistry.counter(ImpressionWriteBuffer.WRITE_FAILED_METRIC).count())
                    .isEqualTo(ImpressionWriteBuffer.MAX_ATTEMPTS + 1);
            verify(counts).increment(TODAY, 101L);
        }

        @Test
        void 커밋_뒤_예상_밖_예외가_나도_작업자가_살아_다음_묶음을_쓴다() throws InterruptedException {
            insertAll();
            doThrow(new IllegalStateException("직렬화 실패")).doNothing().when(counts).increment(any(), any());
            buffer.start();
            buffer.offer(valid(1, false));
            waitUntil(() -> mockingDetailsCalls() == 1);

            buffer.offer(valid(2, false));

            waitUntil(() -> mockingDetailsCalls() == 2);
        }

        @Test
        void 내려갈_때_남은_것을_모두_쓴다() {
            insertAll();
            buffer.start();
            IntStream.range(0, 1_200).forEach(i -> buffer.offer(invalid(i)));

            buffer.stop();

            assertThat(meterRegistry.get(ImpressionWriteBuffer.QUEUE_METRIC).gauge().value()).isZero();
            assertThat(meterRegistry.counter(ImpressionWriteBuffer.LOST_METRIC).count()).isZero();
        }

        private long mockingDetailsCalls() {
            return mockingDetails(impressions).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("insertAllIgnore"))
                    .count();
        }

        private void waitUntil(BooleanSupplier condition) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(condition.getAsBoolean()).isTrue();
        }
    }

    @Nested
    @DisplayName("다시 쓰기 전에 쉬는 시간")
    class Backoff {

        @Test
        void 상한이_200ms_에서_두_배씩_늘고_5초에서_멈춘다() {
            assertThat(ImpressionWriteBuffer.backoff(1, 0.999)).isLessThan(Duration.ofMillis(200));
            assertThat(ImpressionWriteBuffer.backoff(2, 0.999)).isBetween(Duration.ofMillis(390), Duration.ofMillis(400));
            assertThat(ImpressionWriteBuffer.backoff(6, 0.999)).isBetween(Duration.ofMillis(4_990), Duration.ofSeconds(5));
            assertThat(ImpressionWriteBuffer.backoff(30, 0.999)).isLessThanOrEqualTo(Duration.ofSeconds(5));
        }

        @Test
        void 무작위_값이_0_이면_쉬지_않는다() {
            assertThat(ImpressionWriteBuffer.backoff(3, 0)).isZero();
        }
    }

    // 콜백을 그 자리에서 돌리고 안에 있는 동안을 표시한다. 커밋은 통합 테스트가 본다
    private static final class RecordingTransactions extends TransactionTemplate {
        private int executed;
        private boolean inside;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executed++;
            inside = true;
            try {
                return action.doInTransaction(new SimpleTransactionStatus());
            } finally {
                inside = false;
            }
        }
    }
}
