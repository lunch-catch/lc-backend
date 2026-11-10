package com.lunchcatch.billing.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.payment.entity.Payment;
import com.lunchcatch.billing.payment.repository.PaymentRepository;
import com.lunchcatch.billing.reservation.config.RechargeReservationRetryProperties;
import com.lunchcatch.billing.reservation.dto.PointCharged;
import com.lunchcatch.billing.reservation.entity.RechargeReservationOutbox;
import com.lunchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 재예약 워커가 선점한 행을 처리하는 흐름을 본다. 재예약과 행 기록은 목으로 대신하고,
 * 성공, 실패, 영업일이 지난 경우에 어떤 기록을 시켰는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
class RechargeReservationRetryServiceTest {

    // 2026-10-11 10:00 KST
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T01:00:00Z"), ClockConfig.ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 10, 0);
    private static final Long OWNER = 7L;
    private static final long AMOUNT = 30000L;

    @Mock RechargeReservationOutboxService outboxService;
    @Mock RechargeReservationService rechargeReservation;
    @Mock PaymentRepository paymentRepository;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private RechargeReservationRetryService service() {
        return service(20, Duration.ofSeconds(50));
    }

    private RechargeReservationRetryService service(int batchSize, Duration maxRunTime) {
        return service(batchSize, maxRunTime, CLOCK);
    }

    private RechargeReservationRetryService service(int batchSize, Duration maxRunTime, Clock clock) {
        return new RechargeReservationRetryService(outboxService, rechargeReservation, paymentRepository,
                new RechargeReservationRetryProperties(List.of(Duration.ofMinutes(1)), batchSize,
                        Duration.ofMinutes(5), maxRunTime),
                meterRegistry, clock);
    }

    // 재예약 한 건이 걸리는 시간만큼 시계를 민다
    private void reserveTakes(SteppingClock clock, Duration elapsed) {
        doAnswer(invocation -> {
            clock.advance(elapsed);
            return null;
        }).when(rechargeReservation).reserve(any(PointCharged.class));
    }

    private static final class SteppingClock extends Clock {

        private Instant now = CLOCK.instant();

        void advance(Duration elapsed) {
            now = now.plus(elapsed);
        }

        @Override
        public ZoneId getZone() {
            return CLOCK.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private RechargeReservationOutbox row(long outboxId, long paymentId, LocalDate businessDate) {
        RechargeReservationOutbox outbox = RechargeReservationOutbox.pending(paymentId, businessDate, NOW);
        ReflectionTestUtils.setField(outbox, "id", outboxId);
        return outbox;
    }

    private void approvedPayment(long paymentId) {
        Payment payment = Payment.order(OWNER, "ord-" + paymentId, AMOUNT);
        ReflectionTestUtils.setField(payment, "id", paymentId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    }

    private double counted(String result) {
        return meterRegistry.counter(RechargeReservationRetryService.RESERVE_METRIC, "result", result).count();
    }

    @Test
    void 오늘_충전한_행은_재예약하고_닫는다() {
        when(outboxService.claimDue()).thenReturn(List.of(row(1L, 101L, TODAY)));
        approvedPayment(101L);

        int handled = service().reserveDue();

        assertThat(handled).isEqualTo(1);
        verify(rechargeReservation).reserve(new PointCharged(OWNER, 101L, AMOUNT));
        verify(outboxService).complete(1L);
        assertThat(counted("done")).isEqualTo(1.0);
    }

    // 재예약이 실패하는 때는 대부분 DB 가 느린 순간이다. 닫으면 그날 캠페인을 다시 켤 기회를 버린다
    @Test
    void 재예약이_실패하면_닫지_않고_실패를_적어_다시_시도하게_한다() {
        when(outboxService.claimDue()).thenReturn(List.of(row(1L, 101L, TODAY)));
        approvedPayment(101L);
        doThrow(new IllegalStateException("잠금 대기 시간 초과"))
                .when(rechargeReservation).reserve(any(PointCharged.class));

        service().reserveDue();

        verify(outboxService).recordFailure(1L, "IllegalStateException");
        verify(outboxService, never()).complete(any());
        assertThat(counted("failed")).isEqualTo(1.0);
    }

    // 그날 재개할 기회는 지났고 00:00 예약이 그 잔액을 새 날 예산으로 배분했다
    @Test
    void 충전한_영업일이_지난_행은_재예약하지_않고_만료로_닫는다() {
        when(outboxService.claimDue()).thenReturn(List.of(row(1L, 101L, TODAY.minusDays(1))));

        service().reserveDue();

        verify(outboxService).expire(1L);
        verifyNoInteractions(rechargeReservation, paymentRepository);
        assertThat(counted("expired")).isEqualTo(1.0);
    }

    @Test
    void 한_건의_처리가_예외로_끝나도_다음_건을_계속한다() {
        when(outboxService.claimDue()).thenReturn(List.of(row(1L, 101L, TODAY), row(2L, 102L, TODAY)));
        approvedPayment(101L);
        approvedPayment(102L);
        doThrow(new IllegalStateException("커밋 실패")).when(outboxService).complete(1L);

        int handled = service().reserveDue();

        assertThat(handled).isEqualTo(2);
        verify(outboxService).complete(2L);
        assertThat(counted("error")).isEqualTo(1.0);
        assertThat(counted("done")).isEqualTo(1.0);
    }

    // 승인마다 행이 생기므로 한 번에 배치 크기만 처리하면 충전이 몰릴 때 재개가 계속 밀린다
    @Test
    void 선점한_행이_배치_크기만큼이면_같은_실행에서_다시_선점해_빌_때까지_처리한다() {
        when(outboxService.claimDue()).thenReturn(
                List.of(row(1L, 101L, TODAY), row(2L, 102L, TODAY)),
                List.of(row(3L, 103L, TODAY)));
        approvedPayment(101L);
        approvedPayment(102L);
        approvedPayment(103L);

        int handled = service(2, Duration.ofSeconds(50)).reserveDue();

        assertThat(handled).isEqualTo(3);
        verify(outboxService, times(2)).claimDue();
        verify(outboxService).complete(3L);
    }

    // 선점 유지 시간 안에 실행을 끝내야 선점한 행을 다른 서버가 다시 집지 않는다
    @Test
    void 실행_시간_상한에_닿으면_남은_행은_다음_실행에_넘긴다() {
        SteppingClock clock = new SteppingClock();
        when(outboxService.claimDue()).thenReturn(List.of(row(1L, 101L, TODAY), row(2L, 102L, TODAY)));
        approvedPayment(101L);
        approvedPayment(102L);
        reserveTakes(clock, Duration.ofSeconds(30));

        int handled = service(2, Duration.ofSeconds(50), clock).reserveDue();

        assertThat(handled).isEqualTo(2);
        verify(outboxService, times(1)).claimDue();
    }

    // 계정 잠금 대기가 길면 배치 하나가 선점 유지 시간을 넘길 수 있어 행마다 상한을 본다
    @Test
    void 배치_도중에_실행_시간_상한에_닿으면_남은_행은_처리하지_않는다() {
        SteppingClock clock = new SteppingClock();
        when(outboxService.claimDue()).thenReturn(
                List.of(row(1L, 101L, TODAY), row(2L, 102L, TODAY), row(3L, 103L, TODAY)));
        approvedPayment(101L);
        approvedPayment(102L);
        reserveTakes(clock, Duration.ofSeconds(30));

        int handled = service(20, Duration.ofSeconds(50), clock).reserveDue();

        assertThat(handled).isEqualTo(2);
        verify(outboxService, never()).complete(3L);
        verify(paymentRepository, never()).findById(103L);
    }

    @Test
    void 대상이_없으면_0건이다() {
        when(outboxService.claimDue()).thenReturn(List.of());

        assertThat(service().reserveDue()).isZero();
        verifyNoInteractions(rechargeReservation);
    }

    // 결제가 없으면 재예약하지 않고 그 행을 닫지도 않아 선점 만료 뒤 다시 대상이 된다
    @Test
    void 결제가_없으면_재예약하지_않고_오류로_센다() {
        when(outboxService.claimDue()).thenReturn(List.of(row(1L, 101L, TODAY)));
        when(paymentRepository.findById(101L)).thenReturn(Optional.empty());

        int handled = service().reserveDue();

        assertThat(handled).isEqualTo(1);
        verifyNoInteractions(rechargeReservation);
        verify(outboxService, never()).complete(any());
        assertThat(counted("error")).isEqualTo(1.0);
    }
}
