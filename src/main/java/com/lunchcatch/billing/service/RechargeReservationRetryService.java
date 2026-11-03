package com.lunchcatch.billing.service;

import com.lunchcatch.billing.config.RechargeReservationRetryProperties;
import com.lunchcatch.billing.dto.PointCharged;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.RechargeReservationOutbox;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 승인이 남긴 재예약 대기 행을 집어 충전 직후 재예약(I-03)을 한다. 재예약 워커가 쓴다.
 *
 * 트랜잭션을 걸지 않는다. 선점, 재예약, 결과 기록이 각자 짧은 트랜잭션 하나이고, 이 스레드는 그 사이에
 * 커넥션을 쥐지 않는다. 재예약이 커밋된 뒤 결과 기록이 실패하면 선점이 만료된 뒤 다시 재예약한다.
 * 결제×캠페인 멱등 키(uk_point_ledger_recharge_reserve_dedup)가 같은 예약을 두 번 쓰지 않게 막는다.
 *
 * 재예약이 실패하면 닫지 않고 백오프로 다시 시도한다. 실패하는 때는 대부분 DB 가 느린 순간이라
 * 그날 안에 다시 시도하면 캠페인을 다시 켤 수 있다. 충전한 영업일이 지난 행은 재예약하지 않고 닫는다.
 * 그날 재개할 기회는 지났고 00:00 예약이 그 잔액을 새 날 예산으로 배분한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeReservationRetryService {

    static final String RESERVE_METRIC = "billing.recharge.reservation";

    private final RechargeReservationOutboxService outboxService;
    private final RechargeReservationService rechargeReservation;
    private final PaymentRepository paymentRepository;
    private final RechargeReservationRetryProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // 첫 사건 전에도 시리즈가 있어야 increase() 가 첫 증가를 센다
    @PostConstruct
    void registerCounters() {
        for (String result : List.of("done", "failed", "expired", "error")) {
            meterRegistry.counter(RESERVE_METRIC, "result", result);
        }
    }

    /*
     * 재예약할 행을 선점하고 하나씩 처리한다. 한 건이 실패해도 다음 건을 계속한다.
     * 처리 중 예외가 난 건은 선점 만료 시각이 지나면 다시 대상이 된다.
     *
     * 선점한 행이 배치 크기만큼이면 남은 행이 더 있다고 보고 같은 실행에서 다시 선점한다. 승인마다 행이 생기므로
     * 한 번에 배치 크기만 처리하면 충전이 몰릴 때 재개가 계속 밀린다(#1100). 실패한 행은 다음 시각이 미래로,
     * 예외가 난 행은 선점 만료 시각으로 밀려 있어 같은 실행에서 다시 잡히지 않는다.
     *
     * 실행 시간의 상한은 행마다 본다. 계정 잠금 대기가 길면 배치 하나가 선점 유지 시간을 넘겨 다른 서버가 같은 행을
     * 다시 집을 수 있어서다. 상한에 닿으면 선점한 행이 남아 있어도 멈추고, 남은 행은 선점이 만료된 뒤 다시 대상이 된다.
     * 처리를 시도한 건수를 돌려준다.
     */
    public int reserveDue() {
        Instant deadline = clock.instant().plus(properties.maxRunTime());
        int handled = 0;
        List<RechargeReservationOutbox> claimed;
        do {
            claimed = outboxService.claimDue();
            LocalDate today = LocalDate.now(clock);
            for (RechargeReservationOutbox outbox : claimed) {
                if (!clock.instant().isBefore(deadline)) {
                    return handled;
                }
                handleQuietly(outbox, today);
                handled++;
            }
        } while (claimed.size() >= properties.batchSize() && clock.instant().isBefore(deadline));
        return handled;
    }

    private void handleQuietly(RechargeReservationOutbox outbox, LocalDate today) {
        try {
            handle(outbox, today);
        } catch (RuntimeException e) {
            log.warn("event=BILLING_RECHARGE_RESERVE_HANDLE_FAILED paymentId={} exceptionType={}",
                    outbox.getPaymentId(), e.getClass().getSimpleName());
            meterRegistry.counter(RESERVE_METRIC, "result", "error").increment();
        }
    }

    private void handle(RechargeReservationOutbox outbox, LocalDate today) {
        if (outbox.getBusinessDate().isBefore(today)) {
            outboxService.expire(outbox.getId());
            log.error("event=BILLING_RECHARGE_RESERVE_EXPIRED paymentId={} businessDate={} attemptCount={} "
                            + "lastError={} fallback=NEXT_DAILY_RESERVE",
                    outbox.getPaymentId(), outbox.getBusinessDate(), outbox.getAttemptCount(), outbox.getLastError());
            meterRegistry.counter(RESERVE_METRIC, "result", "expired").increment();
            return;
        }
        Payment payment = paymentRepository.findById(outbox.getPaymentId())
                .orElseThrow(() -> new BillingException(BillingErrorCode.PAYMENT_NOT_FOUND));
        try {
            rechargeReservation.reserve(new PointCharged(payment.getOwnerId(), payment.getId(), payment.getAmount()));
        } catch (RuntimeException e) {
            // 예외 메시지에는 실행한 SQL 과 값이 들어갈 수 있어 종류만 남긴다(SEC-4-02)
            String exceptionType = e.getClass().getSimpleName();
            outboxService.recordFailure(outbox.getId(), exceptionType);
            log.warn("event=BILLING_RECHARGE_RESERVE_FAILED ownerId={} paymentId={} attemptCount={} "
                            + "exceptionType={} fallback=RETRY",
                    payment.getOwnerId(), payment.getId(), outbox.getAttemptCount() + 1, exceptionType);
            meterRegistry.counter(RESERVE_METRIC, "result", "failed").increment();
            return;
        }
        outboxService.complete(outbox.getId());
        meterRegistry.counter(RESERVE_METRIC, "result", "done").increment();
    }
}
