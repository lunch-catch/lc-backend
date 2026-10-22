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
import com.launchcatch.coupon.service.model.CouponIssuanceOutcome;
import com.launchcatch.coupon.service.model.CouponIssuanceResult;
import com.launchcatch.coupon.service.model.CouponReservation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
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
        var request = new com.launchcatch.coupon.service.model.CouponIssuanceRequest(17L, 81L,
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
