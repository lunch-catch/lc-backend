package com.lunchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.contract.CandidateStateUpdater;
import com.lunchcatch.coupon.CouponTestProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class CouponSoldOutNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 21);
    private final CouponLookUpService lookUp = mock(CouponLookUpService.class);
    private final CandidateStateUpdater candidates = mock(CandidateStateUpdater.class);
    private final Clock clock = mock(Clock.class);
    private final CouponSoldOutNotificationService service = new CouponSoldOutNotificationService(CouponTestProperties.defaults(), lookUp, candidates, clock);
    private final CouponLookUpService.SoldOut soldOut = new CouponLookUpService.SoldOut(91L, DATE, DATE.atTime(12, 0));

    @BeforeEach
    void acceptRequestsWithoutStartingTheWorker() {
        when(clock.instant()).thenReturn(NOW);
        ReflectionTestUtils.setField(service, "running", true);
    }

    @AfterEach
    void stop() {
        service.stop();
        org.slf4j.MDC.clear();
    }

    @Test
    void 접수는_DB나_광고_응답을_기다리지_않는다() {
        service.request(81L);

        verifyNoInteractions(lookUp, candidates);
    }

    @Test
    void 같은_이벤트를_여러_번_접수해도_성공한_통보는_한_번만_실행한다() {
        when(lookUp.findSoldOut(81L)).thenReturn(Optional.of(soldOut));
        service.request(81L);
        service.request(81L);
        service.processPending();
        service.request(81L);
        service.processPending();

        verify(candidates).markSoldOut(91L, DATE, soldOut.soldOutAt());
    }

    @Test
    void 통보_실패는_새_요청_없이_5초_뒤에_재시도한다() {
        when(lookUp.findSoldOut(81L)).thenReturn(Optional.of(soldOut));
        doThrow(new DataAccessResourceFailureException("unavailable")).doNothing()
                .when(candidates).markSoldOut(91L, DATE, soldOut.soldOutAt());
        service.request(81L);
        service.processPending();
        service.request(81L);
        service.processPending();
        verify(candidates).markSoldOut(91L, DATE, soldOut.soldOutAt());

        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        service.processPending();

        verify(candidates, times(2)).markSoldOut(91L, DATE, soldOut.soldOutAt());
    }

    @Test
    void DB_조회에_실패한_이벤트도_다시_처리한다() {
        when(lookUp.findSoldOut(81L)).thenThrow(new DataAccessResourceFailureException("unavailable"))
                .thenReturn(Optional.of(soldOut));
        service.request(81L);
        service.processPending();
        verifyNoInteractions(candidates);

        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        service.processPending();

        verify(candidates).markSoldOut(91L, DATE, soldOut.soldOutAt());
    }

    @Test
    void DB에서_소진을_확인할_수_없으면_통보하지_않고_다시_확인한다() {
        when(lookUp.findSoldOut(81L)).thenReturn(Optional.empty(), Optional.of(soldOut));
        service.request(81L);
        service.processPending();
        verifyNoInteractions(candidates);

        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        service.processPending();

        verify(candidates).markSoldOut(91L, DATE, soldOut.soldOutAt());
    }

    @Test
    void 한_시간이_지난_미완료_작업은_더_실행하지_않는다() {
        service.request(81L);
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(1)));

        service.processPending();

        verifyNoInteractions(lookUp, candidates);
    }

    @Test
    void 대기_작업은_2000개를_넘지_않는다() {
        when(lookUp.findSoldOut(anyLong())).thenReturn(Optional.of(soldOut));
        for (long eventId = 1; eventId <= 2001; eventId++) {
            service.request(eventId);
        }

        service.processPending();

        verify(lookUp, times(2000)).findSoldOut(anyLong());
    }

    @Test
    void 중지된_작업자는_새_작업을_받지_않는다() {
        service.stop();
        service.request(81L);
        service.processPending();

        assertThat(service.isRunning()).isFalse();
        verifyNoInteractions(lookUp, candidates);
    }

    @Test
    void 재시도도_접수할_때의_MDC를_사용하고_실행_전의_MDC로_돌아간다() {
        org.slf4j.MDC.put("traceId", "request-1");
        when(lookUp.findSoldOut(81L)).thenReturn(Optional.of(soldOut));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("secret-token",
                new java.sql.SQLException("secret-value", "23000", 1062))).doNothing()
                .when(candidates).markSoldOut(91L, DATE, soldOut.soldOutAt());
        service.request(81L);
        org.slf4j.MDC.put("traceId", "scheduler");
        try (var logs = new CouponLogCapture(CouponSoldOutNotificationService.class)) {
            service.processPending();
            assertThat(org.slf4j.MDC.get("traceId")).isEqualTo("scheduler");
            when(clock.instant()).thenReturn(NOW.plusSeconds(5));
            service.processPending();

            assertThat(logs.events).hasSize(2).allSatisfy(event -> {
                assertThat(event.getMDCPropertyMap()).containsEntry("traceId", "request-1");
                assertThat(event.getFormattedMessage()).doesNotContain("secret-token", "secret-value");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(logs.events.getFirst().getFormattedMessage()).contains("sqlState=23000", "fallback=RETRY");
            assertThat(logs.events.getLast().getFormattedMessage()).contains("COUPON_SOLD_OUT_NOTIFICATION_DONE");
            assertThat(org.slf4j.MDC.get("traceId")).isEqualTo("scheduler");
        }
    }

    @Test
    void 느린_통보_중에도_같은_이벤트의_접수는_바로_끝난다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(lookUp.findSoldOut(81L)).thenAnswer(invocation -> {
            started.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(soldOut);
        });
        ReflectionTestUtils.setField(service, "running", false);
        service.start();
        service.start();
        service.request(81L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> service.request(81L)).get(500, TimeUnit.MILLISECONDS);
                assertThat(service.isRunning()).isTrue();
                var stopping = executor.submit(() -> service.stop());
                await().atMost(Duration.ofSeconds(3)).until(() -> !service.isRunning());
                executor.submit(() -> service.request(82L)).get(500, TimeUnit.MILLISECONDS);
                service.start();
                assertThat(service.isRunning()).isFalse();
                finish.countDown();
                stopping.get(3, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(
                () -> verify(candidates).markSoldOut(91L, DATE, soldOut.soldOutAt()));
    }
}
