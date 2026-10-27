package com.lunchcatch.billing.service;

import com.lunchcatch.billing.config.PaymentRecheckProperties;
import com.lunchcatch.billing.dto.PointCharged;
import com.lunchcatch.billing.entity.LedgerSource;
import com.lunchcatch.billing.entity.OutboxStatus;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PaymentReconciliationOutbox;
import com.lunchcatch.billing.entity.PaymentStatus;
import com.lunchcatch.billing.entity.PointLedger;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PaymentReconciliationOutboxRepository;
import com.lunchcatch.billing.repository.PaymentRepository;
import com.lunchcatch.billing.repository.PointAccountRepository;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 결제 결과를 기록한다. 승인 확정, 웹훅, 재조회 워커, 관리자 재조회가 모두 이 메서드들을 쓴다.
 *
 * 메서드마다 짧은 트랜잭션 하나이고 결제대행사를 부르지 않는다. 호출은 부르는 쪽이 트랜잭션 밖에서 끝낸 뒤
 * 결과만 들고 온다.
 *
 * 모든 기록은 결제 행을 잠그고(FOR UPDATE) 상태를 다시 읽은 뒤에 한다. 이미 결과가 확정된 결제
 * (APPROVED, FAILED, CANCELLED)면 아무것도 쓰지 않고 그 결제를 그대로 돌려준다. 같은 결제를 두 경로가
 * 동시에 확정하려 할 때 뒤에 온 쪽이 앞의 결과를 덮지 않게 하려는 것이다. 예를 들어 승인 확정이 두 번
 * 동시에 오면 한쪽은 승인되고 다른 쪽은 결제대행사의 거절을 받을 수 있는데, 거절이 승인을 실패로 덮으면 안 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentResultService {

    private static final String EXHAUSTED_METRIC = "billing.payment.recheck.exhausted";

    private final PaymentRepository paymentRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final PointAccountRepository pointAccountRepository;
    private final PaymentReconciliationOutboxRepository outboxRepository;
    private final PaymentRecheckProperties recheckProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // 첫 사건 전에도 시리즈가 있어야 increase() 가 첫 증가를 센다
    @PostConstruct
    void registerCounters() {
        meterRegistry.counter(EXHAUSTED_METRIC);
    }

    /*
     * 승인을 시도하기 직전에 결제 식별자를 적는다. 결제대행사를 부르기 전에 이 트랜잭션만 먼저 커밋한다.
     * 호출 중 서버가 죽어도 어떤 식별자로 승인을 시도했는지 남아야 재조회와 대사가 이 결제를 찾는다.
     *
     * 잠근 뒤 이미 승인 시도 전 상태가 아니면 아무것도 하지 않는다. 같은 주문의 승인 확정이 동시에 와서
     * 다른 요청이 먼저 결과를 냈다는 뜻이다.
     *
     * 먼저 커밋된 식별자가 있으면 같은 값만 허용한다. 승인 결과를 기다리는 동안 상태가 REQUESTED 여도
     * 다른 식별자로 바꾸면 먼저 보낸 승인 요청을 찾을 수 없다.
     *
     * 다른 결제가 이미 같은 식별자를 갖고 있으면 uk_payment_pg_tid 위반이 이 메서드 안에서 난다.
     * 커밋까지 미루지 않고 flush 로 바로 드러내 부른 쪽이 오류 코드로 바꿀 수 있게 한다.
     */
    @Transactional
    public Payment assignPgTid(Long paymentId, String pgTid) {
        Payment payment = lock(paymentId);
        if (payment.getStatus() != PaymentStatus.REQUESTED) {
            return payment;
        }
        if (payment.getPgTid() != null && !payment.getPgTid().equals(pgTid)) {
            throw new BillingException(BillingErrorCode.ORDER_ID_MISMATCH);
        }
        payment.assignPgTid(pgTid);
        paymentRepository.flush();
        return payment;
    }

    /*
     * 승인으로 확정하고 충전(CHARGE)을 기록한다.
     *
     * 같은 트랜잭션에서 점주 계정 행이 없으면 만든다. 환불 승인, 음수 조정, 00:00 예약이 그 행을 잠그므로
     * 잔액이 생기는 첫 순간에 있어야 한다. 재조회 아웃박스가 있으면 닫는다.
     *
     * 충전 통보는 새 CHARGE 행을 만든 이 경로에서만 낸다. 받는 쪽은 커밋 뒤에 돌므로 롤백되면 통보도 없다.
     * 승인 시각이 비어 있으면 지금 시각을 쓴다. 결제대행사 조회 결과에 승인 시각이 빠지는 경우가 있다.
     */
    @Transactional
    public Payment approve(Long paymentId, LocalDateTime approvedAt) {
        Payment payment = lock(paymentId);
        if (isDetermined(payment)) {
            return payment;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        payment.approve(approvedAt != null ? approvedAt : now);
        pointAccountRepository.createIfAbsent(payment.getOwnerId(), now);
        pointLedgerRepository.save(PointLedger.charge(
                payment.getOwnerId(), payment.getId(), payment.getAmount(), LedgerSource.REALTIME));
        closeOutbox(paymentId, now);
        eventPublisher.publishEvent(new PointCharged(payment.getOwnerId(), payment.getId(), payment.getAmount()));
        return payment;
    }

    // 결제대행사가 거절했거나 조회 결과가 실패다
    @Transactional
    public Payment fail(Long paymentId, String failureReason) {
        Payment payment = lock(paymentId);
        if (isDetermined(payment)) {
            return payment;
        }
        payment.fail(failureReason);
        closeOutbox(paymentId, LocalDateTime.now(clock));
        return payment;
    }

    /*
     * 결제대행사 조회로 승인 전에 만료되거나 취소된 것이 확인됐다. 충전은 없다.
     * 결제대행사의 취소 식별자는 남기지 않는다. 승인 전 만료에는 그 식별자가 없다.
     */
    @Transactional
    public Payment cancel(Long paymentId) {
        Payment payment = lock(paymentId);
        if (isDetermined(payment)) {
            return payment;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        payment.cancel(now, null);
        closeOutbox(paymentId, now);
        return payment;
    }

    /*
     * 승인 결과를 확정하지 못했다. UNKNOWN 으로 두고 재조회 아웃박스 행을 같은 트랜잭션에서 만든다.
     * 이미 UNKNOWN 이면 아웃박스도 이미 있으므로 아무것도 하지 않는다(결제당 한 행).
     */
    @Transactional
    public Payment markUnknown(Long paymentId) {
        Payment payment = lock(paymentId);
        if (isDetermined(payment) || payment.getStatus() == PaymentStatus.UNKNOWN) {
            return payment;
        }
        payment.markUnknown();
        LocalDateTime nextRetryAt = LocalDateTime.now(clock).plus(recheckProperties.firstDelay());
        outboxRepository.save(
                PaymentReconciliationOutbox.pending(paymentId, recheckProperties.maxRetry(), nextRetryAt));
        return payment;
    }

    /*
     * 재조회 워커가 결제대행사에 물었지만 결과를 확정하지 못했다(처리 중, 조회 불가, 조회 실패).
     * 시도 횟수를 늘리고 다음 시각을 간격 목록과 지터로 정한다. 결제는 UNKNOWN 으로 그대로 둔다.
     *
     * 그 사이에 웹훅이나 관리자가 결제를 확정했으면 아웃박스도 이미 닫혔으므로 아무것도 하지 않는다.
     * 시도 횟수가 상한에 닿으면 아웃박스를 FAILED_PERMANENT 로 두고 ERROR 로그와 지표를 남긴다.
     * 돈이 걸린 결제를 서버가 임의로 닫지 않고 관리자 재조회로 사람이 확정하게 하려는 것이다.
     */
    @Transactional
    public Payment recordRecheckMiss(Long paymentId, String reason) {
        Payment payment = lock(paymentId);
        if (isDetermined(payment)) {
            return payment;
        }
        outboxRepository.findByPaymentId(paymentId)
                .filter(outbox -> outbox.getStatus() == OutboxStatus.PENDING)
                .ifPresent(outbox -> recordMiss(payment, outbox, reason));
        return payment;
    }

    /*
     * 관리자가 재조회했지만 확정하지 못했다. 시도 횟수와 상태는 그대로 두고 확인 시각과 사유만 남긴다.
     * 상한을 넘긴 건을 다시 조회했다고 자동 재시도가 되살아나면 안 된다.
     */
    @Transactional
    public Payment recordAdminRecheckMiss(Long paymentId, String reason) {
        Payment payment = lock(paymentId);
        if (isDetermined(payment)) {
            return payment;
        }
        outboxRepository.findByPaymentId(paymentId)
                .ifPresent(outbox -> outbox.touch(LocalDateTime.now(clock), reason));
        return payment;
    }

    private void recordMiss(Payment payment, PaymentReconciliationOutbox outbox, String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime nextRetryAt = now.plus(
                recheckProperties.delayAfter(outbox.getAttemptCount() + 1, ThreadLocalRandom.current().nextDouble()));
        if (outbox.recordMiss(now, reason, nextRetryAt)) {
            log.error("event=BILLING_PAYMENT_RECHECK_EXHAUSTED paymentId={} attemptCount={} lastError={}",
                    payment.getId(), outbox.getAttemptCount(), outbox.getLastError());
            meterRegistry.counter(EXHAUSTED_METRIC).increment();
        }
    }

    private Payment lock(Long paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.PAYMENT_NOT_FOUND));
    }

    private boolean isDetermined(Payment payment) {
        PaymentStatus status = payment.getStatus();
        return status == PaymentStatus.APPROVED || status == PaymentStatus.FAILED
                || status == PaymentStatus.CANCELLED;
    }

    private void closeOutbox(Long paymentId, LocalDateTime now) {
        outboxRepository.findByPaymentId(paymentId).ifPresent(outbox -> outbox.resolve(now));
    }
}
