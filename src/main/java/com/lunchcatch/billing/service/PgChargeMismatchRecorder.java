package com.lunchcatch.billing.service;

import com.lunchcatch.billing.entity.LedgerSource;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PointLedger;
import com.lunchcatch.billing.entity.PgChargeReconciliationPending;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.billing.repository.PgChargeReconciliationPendingRepository;
import com.lunchcatch.billing.repository.SettlementMismatchRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * PG_VS_CHARGE 대사(13번)가 찾은 결과를 기록한다. 한 번 부르는 것이 트랜잭션 하나다.
 *
 * 대사는 PG를 조회하므로 트랜잭션을 걸지 않는다. PG 응답이 늦어지는 동안 DB 연결과 트랜잭션을
 * 쥐고 있지 않도록, DB에 쓰는 이 부분만 따로 짧게 묶는다. 같은 클래스 안에서 부르면 프록시를
 * 거치지 않아 트랜잭션이 걸리지 않으므로 빈을 나눈다(SpendMismatchRecorder와 같은 이유).
 */
@Service
@RequiredArgsConstructor
public class PgChargeMismatchRecorder {

    private final PointLedgerRepository ledgerRepository;
    private final SettlementMismatchRepository mismatchRepository;
    private final PgChargeReconciliationPendingRepository pendingRepository;

    /*
     * PG와 payment 양쪽이 같은 금액을 승인으로 보는데 원장에 CHARGE가 없을 때 보정한다.
     * CHARGE를 기록하고 그 불일치를 LEDGER_CORRECTION으로 닫힌 채 남긴다(resolved_by NULL).
     * PointCharged는 발행하지 않는다 — 이 메서드는 그 이벤트의 퍼블리셔를 아예 모른다.
     * 재검증 대기도 같은 트랜잭션에서 지운다.
     */
    @Transactional
    public void recordMissingCharge(Payment payment, LocalDate businessDate, Long jobExecutionId,
                                    LocalDateTime now) {
        ledgerRepository.save(
                PointLedger.charge(payment.getOwnerId(), payment.getId(), payment.getAmount(),
                        LedgerSource.RECONCILIATION));
        mismatchRepository.insertPgChargeMismatchResolvedIfAbsent(
                businessDate, payment.getId(), payment.getAmount(), 0L, jobExecutionId, now);
        pendingRepository.deleteByPaymentId(payment.getId());
    }

    /*
     * PG와 payment가 서로 다른 금액을 승인으로 본다. 자동 복구 없이 미해결 불일치만 남긴다.
     * 재검증 대기도 같은 트랜잭션에서 지운다. 따로 지우다 실패하면 불일치는 남고 대기도 남아 다음 날 같은 결제를 다시 본다.
     */
    @Transactional
    public void recordMismatch(Long paymentId, long expectedAmount, long actualAmount,
                               LocalDate businessDate, Long jobExecutionId, LocalDateTime now) {
        mismatchRepository.insertPgChargeMismatchIfAbsent(
                businessDate, paymentId, expectedAmount, actualAmount, jobExecutionId, now);
        pendingRepository.deleteByPaymentId(paymentId);
    }

    @Transactional
    public void recordPending(Long paymentId, LocalDate businessDate, String reason) {
        if (!pendingRepository.existsByPaymentId(paymentId)) {
            pendingRepository.save(PgChargeReconciliationPending.pending(paymentId, businessDate, reason));
        }
    }

    @Transactional
    public void resolvePending(Long paymentId) {
        pendingRepository.deleteByPaymentId(paymentId);
    }
}
