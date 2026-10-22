package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.model.CouponIssuanceOutcome;
import com.launchcatch.coupon.model.CouponIssuanceResult;
import com.launchcatch.coupon.model.CouponReservation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class CouponIssueBatcherTest {

    private final CouponBatchIssuanceService batchService = mock(CouponBatchIssuanceService.class);
    private final CouponSequenceService sequenceService = mock(CouponSequenceService.class);
    private final CouponSoldOutNotificationService soldOutNotifications = mock(CouponSoldOutNotificationService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"), ZoneOffset.ofHours(9));
    private final CouponIssueBatcher batcher = new CouponIssueBatcher(batchService, sequenceService, soldOutNotifications, clock);
    private final CouponReservation reservation =
            new CouponReservation(81L, 1, "reservation");
    private final CouponIssueResponse response = new CouponIssueResponse(1001L, OffsetDateTime.now(clock));
    private final CouponIssuanceOutcome issued = CouponIssuanceOutcome.issued(response, reservation);

    @BeforeEach
    void startWorker() {
        ReflectionTestUtils.setField(batcher, "workerCount", 1);
        ReflectionTestUtils.setField(batcher, "maxSize", 8);
        ReflectionTestUtils.setField(batcher, "queueCapacity", 10);
        ReflectionTestUtils.setField(batcher, "waitTimeout", Duration.ofSeconds(5));
        batcher.start();
    }

    @AfterEach
    void stopWorker() {
        batcher.stop();
    }

    @Test
    void 예약_완료가_실패하면_요청에_오류를_전달하고_다음_요청을_처리한다() {
        RuntimeException failure = new IllegalStateException("completion failed");
        when(batchService.issueBatch(anyList(), any())).thenReturn(List.of(issued));
        when(sequenceService.completeAll(List.of(reservation))).thenThrow(failure).thenReturn(Set.of());

        assertThatThrownBy(() -> batcher.submitAndAwait(17L, 81L)).isSameAs(failure);
        assertThat(batcher.submitAndAwait(18L, 81L).response()).isEqualTo(response);
    }

    @Test
    void 미완료_예약을_기다리다가_재고가_반납되면_다시_발급한다() {
        when(batchService.issueBatch(anyList(), any()))
                .thenReturn(List.of(CouponIssuanceOutcome.awaitingReservationResult())).thenReturn(List.of(issued));
        when(sequenceService.retryAvailability(81L)).thenReturn(2).thenReturn(1);

        assertThat(batcher.submitAndAwait(17L, 81L).response()).isEqualTo(response);
        org.mockito.Mockito.verifyNoInteractions(soldOutNotifications);
    }

    @Test
    void 같은_이벤트의_예약_조회가_진행_중이면_후속_요청도_기다렸다가_소진으로_끝낸다() throws Exception {
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch finishPolling = new CountDownLatch(1);
        CountDownLatch secondBatch = new CountDownLatch(1);
        when(batchService.issueBatch(anyList(), any())).thenAnswer(invocation -> {
            List<com.launchcatch.coupon.model.CouponIssuanceRequest> requests = invocation.getArgument(0);
            if (requests.getFirst().memberId() == 18L) {
                secondBatch.countDown();
            }
            return java.util.Collections.nCopies(requests.size(), CouponIssuanceOutcome.awaitingReservationResult());
        });
        when(sequenceService.retryAvailability(81L)).thenAnswer(invocation -> {
            polling.countDown();
            assertThat(finishPolling.await(5, TimeUnit.SECONDS)).isTrue();
            return 0;
        });

        try (var callers = Executors.newFixedThreadPool(2)) {
            try {
                var first = callers.submit(() -> batcher.submitAndAwait(17L, 81L));
                assertThat(polling.await(5, TimeUnit.SECONDS)).isTrue();
                var second = callers.submit(() -> batcher.submitAndAwait(18L, 81L));
                assertThat(secondBatch.await(5, TimeUnit.SECONDS)).isTrue();
                BlockingQueue<?> waiting = (BlockingQueue<?>) ReflectionTestUtils.getField(batcher, "awaitingReservations");
                await().during(Duration.ofMillis(50)).atMost(Duration.ofSeconds(2)).until(() -> waiting.size() == 1);
                assertThat(second).isNotDone();
                finishPolling.countDown();

                assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).satisfies(error ->
                        assertThat(((CouponException) error.getCause()).getErrorCode())
                                .isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED));
                assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).satisfies(error ->
                        assertThat(((CouponException) error.getCause()).getErrorCode())
                                .isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED));
            } finally {
                finishPolling.countDown();
            }
        }
    }

    @Test
    void 예약_대기_조회가_실패하면_원래_오류를_요청에_전달한다() {
        RuntimeException failure = CouponException.recoveryRequired(81L);
        when(batchService.issueBatch(anyList(), any())).thenReturn(List.of(CouponIssuanceOutcome.awaitingReservationResult()));
        when(sequenceService.retryAvailability(81L)).thenThrow(failure);

        assertThatThrownBy(() -> batcher.submitAndAwait(17L, 81L)).isSameAs(failure);
    }

    @Test
    void 요청을_모으는_중_작업자가_중단되면_모은_요청도_혼잡으로_완료한다() throws Exception {
        CountDownLatch collecting = new CountDownLatch(1);
        CountDownLatch finishCollecting = new CountDownLatch(1);
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(10) {
            @Override
            public Object poll(long timeout, TimeUnit unit) throws InterruptedException {
                if (unit == TimeUnit.NANOSECONDS) {
                    collecting.countDown();
                    finishCollecting.await();
                }
                return super.poll(timeout, unit);
            }
        };
        ReflectionTestUtils.setField(batcher, "queue", queue);
        @SuppressWarnings("unchecked")
        List<Thread> workers = (List<Thread>) ReflectionTestUtils.getField(batcher, "workers");

        try (var caller = Executors.newSingleThreadExecutor()) {
            try {
                var result = caller.submit(() -> batcher.submitAndAwait(17L, 81L));
                assertThat(collecting.await(5, TimeUnit.SECONDS)).isTrue();
                workers.getFirst().interrupt();

                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).satisfies(error ->
                        assertThat(((CouponException) error.getCause()).getErrorCode())
                                .isEqualTo(CouponErrorCode.ISSUANCE_BUSY));
                org.mockito.Mockito.verifyNoInteractions(batchService, sequenceService, soldOutNotifications);
            } finally {
                finishCollecting.countDown();
            }
        }
    }

    @Test
    void 커밋_전_실행_기한이_끝나면_요청을_취소하고_발급을_막는다() throws Exception {
        ReflectionTestUtils.setField(batcher, "waitTimeout", Duration.ofMillis(200));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(batchService.issueBatch(anyList(), any())).thenAnswer(invocation -> {
            started.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            invocation.getArgument(1, com.launchcatch.coupon.service.model.CouponBatchDeadline.class).check();
            return List.of(issued);
        });

        try (var caller = Executors.newSingleThreadExecutor()) {
            try {
                var result = caller.submit(() -> batcher.submitAndAwait(17L, 81L));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).satisfies(error ->
                        assertThat(((CouponException) error.getCause()).getErrorCode())
                                .isEqualTo(CouponErrorCode.ISSUANCE_BUSY));
            } finally {
                finish.countDown();
            }
        }
        org.mockito.Mockito.verifyNoInteractions(sequenceService, soldOutNotifications);
    }

    @Test
    void HTTP가_기존_요청을_마친_뒤_발급_작업자를_종료한다() {
        assertThat(batcher.getPhase()).isPositive().isLessThan(org.springframework.context.SmartLifecycle.DEFAULT_PHASE);
    }

    @Test
    void 예약을_완료한_뒤_발급_응답과_소진_여부를_돌려준다() {
        when(batchService.issueBatch(anyList(), any())).thenReturn(List.of(issued));
        when(sequenceService.completeAll(List.of(reservation))).thenReturn(Set.of(81L));

        CouponIssuanceResult result = batcher.submitAndAwait(17L, 81L);

        assertThat(result.response()).isEqualTo(response);
        assertThat(result.soldOut()).isTrue();
        verify(soldOutNotifications).request(81L);
        verify(sequenceService).completeAll(List.of(reservation));
    }

    @Test
    void 커밋된_결과에서_순번을_못_받은_요청만_복구_후_재접수를_허용한다() {
        when(batchService.issueBatch(anyList(), any())).thenReturn(List.of(CouponIssuanceOutcome.requiringRecovery()));

        assertThatThrownBy(() -> batcher.submitAndAwait(17L, 81L))
                .isInstanceOfSatisfying(com.launchcatch.coupon.exception.CouponException.class,
                        error -> assertThat(error.canResumeIssuance(81L)).isTrue());
    }

    @Test
    void 처리_기한이_이미_끝났으면_대기열에_등록하지_않는다() {
        var request = new com.launchcatch.coupon.model.CouponIssuanceRequest(17L, 81L,
                java.time.LocalDateTime.now(clock));

        assertThatThrownBy(() -> batcher.submitAndAwait(request, System.nanoTime() - 1))
                .isInstanceOf(com.launchcatch.coupon.exception.CouponException.class);
        org.mockito.Mockito.verifyNoInteractions(batchService);
    }

    @Test
    void 예상하지_못한_작업자_오류도_요청을_실패로_완료하고_다음_요청을_처리한다() {
        AssertionError failure = new AssertionError("unexpected worker failure");
        when(batchService.issueBatch(anyList(), any())).thenThrow(failure).thenReturn(List.of(issued));

        assertThatThrownBy(() -> batcher.submitAndAwait(17L, 81L)).rootCause().isSameAs(failure);
        assertThat(batcher.submitAndAwait(18L, 81L).response()).isEqualTo(response);
    }

    @Test
    void 종료는_실행_중인_묶음을_마치고_대기열의_요청을_거절한다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(batchService.issueBatch(anyList(), any())).thenAnswer(invocation -> {
            started.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of(issued);
        });
        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var first = executor.submit(() -> batcher.submitAndAwait(17L, 81L));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                var waiting = executor.submit(() -> batcher.submitAndAwait(18L, 81L));
                BlockingQueue<?> queue = (BlockingQueue<?>) ReflectionTestUtils.getField(batcher, "queue");
                await().atMost(Duration.ofSeconds(5)).until(() -> queue.size() == 1);
                var stopping = executor.submit(() -> {
                    batcher.stop();
                });
                await().atMost(Duration.ofSeconds(5)).until(() -> !batcher.isRunning());
                finish.countDown();

                assertThat(first.get(5, TimeUnit.SECONDS).response()).isEqualTo(response);
                assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(com.launchcatch.coupon.exception.CouponException.class);
                stopping.get(5, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void 묶음이_실패하면_나눠서_발급하지_않고_모든_요청에_오류를_전달한다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        RuntimeException failure = new DataAccessResourceFailureException("DB connection failed");
        when(batchService.issueBatch(anyList(), any())).thenAnswer(invocation -> {
            List<?> requests = invocation.getArgument(0);
            if (requests.size() > 1) {
                throw failure;
            }
            started.countDown();
            assertThat(finishFirst.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of(issued);
        });

        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var first = executor.submit(() -> batcher.submitAndAwait(17L, 81L));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> batcher.submitAndAwait(18L, 81L));
                var third = executor.submit(() -> batcher.submitAndAwait(19L, 81L));
                BlockingQueue<?> queue = (BlockingQueue<?>) ReflectionTestUtils.getField(batcher, "queue");
                await().atMost(Duration.ofSeconds(5)).until(() -> queue.size() == 2);
                finishFirst.countDown();

                assertThat(first.get(5, TimeUnit.SECONDS).response()).isEqualTo(response);
                assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasCause(failure);
                assertThatThrownBy(() -> third.get(5, TimeUnit.SECONDS)).hasCause(failure);
                assertThat(batcher.submitAndAwait(20L, 81L).response()).isEqualTo(response);
            } finally {
                finishFirst.countDown();
            }
        }
    }

    @Test
    void 커밋이_시작된_요청은_기한이_지나도_실제_완료를_기다린다() throws Exception {
        ReflectionTestUtils.setField(batcher, "waitTimeout", Duration.ofMillis(100));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch returnResult = new CountDownLatch(1);
        when(batchService.issueBatch(anyList(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, com.launchcatch.coupon.service.model.CouponBatchDeadline.class).beginCommit();
            started.countDown();
            assertThat(returnResult.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of(issued);
        });
        when(sequenceService.completeAll(List.of(reservation))).thenReturn(Set.of(81L));

        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> batcher.submitAndAwait(17L, 81L));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                await().during(Duration.ofMillis(150)).atMost(Duration.ofSeconds(1))
                        .untilAsserted(() -> assertThat(result).isNotDone());
            } finally {
                returnResult.countDown();
            }
            assertThat(result.get(5, TimeUnit.SECONDS).response()).isEqualTo(response);
        }

        verify(sequenceService).completeAll(List.of(reservation));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(soldOutNotifications).request(81L));
    }
}
