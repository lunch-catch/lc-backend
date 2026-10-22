package com.launchcatch.billing.service;

import com.launchcatch.billing.client.PaymentGateway;
import com.launchcatch.billing.client.PaymentGatewayInquiryResult;
import com.launchcatch.billing.client.PaymentGatewayInquiryResult.Status;
import com.launchcatch.billing.client.PgNotSentException;
import com.launchcatch.billing.client.PgUnknownException;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.repository.PaymentRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 전일 생성된 결제를 PG 결제 조회로 대사한다(PG_VS_CHARGE, 배치 설계 I-05, 구현 순서 13번).
 * 01:30 전일분 1회만 실행하고, pg_tid 가 있는 결제를 건별로 조회한다. PG 거래 목록 API 는
 * 쓰지 않는다.
 *
 * 비교는 "서로 얼마를 승인된 것으로 보는가"로 통일한다. ourClaim 은 payment.status 가
 * APPROVED 일 때만 그 금액이고, pgClaim 은 PG 상태가 APPROVED 일 때만 PG 응답 금액이다.
 * 둘이 같고 0보다 크고 원장에 CHARGE 가 없으면 빠진 CHARGE 로 보고 자동 복구한다. 둘이
 * 다르면(금액이 다르거나 한쪽만 승인으로 보는 경우 전부) 자동 복구 없이 미해결 불일치만
 * 남긴다. PG 가 PENDING 이면 아직 결론이 안 난 것이라 건너뛴다.
 *
 * PG 조회는 트랜잭션 밖에서 부른다. 응답을 기다리는 동안 DB 연결과 트랜잭션을 쥐고 있지 않기
 * 위해서다. DB 에 쓰는 부분만 PgChargeMismatchRecorder 의 트랜잭션으로 짧게 묶는다.
 *
 * 한 건의 조회나 기록이 실패해도 나머지 결제는 계속 처리한다(PaymentRecheckService와 같은
 * 이유). 이 작업은 날짜별로 한 번만 돌아 그 영업일을 다시 보지 않으므로, 조회 자체가 실패한
 * 결제는 짧게 1회만 재시도하고 그래도 실패하면 불일치로 기록하지 않는다. "불일치"가 아니라
 * "확인 못 함"이기 때문이다(2026-10-16 이슈에서 정함, #391). ERROR 로그와 지표만 남기고
 * 넘어간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgChargeReconciliationService {

    static final String RECON_METRIC = "billing.pg.recon";

    // 대사 시각 직전 이 시간 안에 커밋된 결제는 비교에서 뺀다(워터마크)
    private static final int WATERMARK_MINUTES = 5;

    private final PaymentRepository paymentRepository;
    private final PointLedgerRepository ledgerRepository;
    private final PaymentGateway paymentGateway;
    private final PgChargeMismatchRecorder mismatchRecorder;
    private final BatchExecutionQueryService batchExecutions;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // runDate 의 전날을 대사하고 기록한 불일치(보정 포함) 수를 돌려준다
    public int reconcilePreviousDay(LocalDate runDate, String jobName) {
        LocalDate target = runDate.minusDays(1);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = target.atStartOfDay();
        LocalDateTime targetEnd = runDate.atStartOfDay();
        LocalDateTime watermark = now.minusMinutes(WATERMARK_MINUTES);
        LocalDateTime to = watermark.isBefore(targetEnd) ? watermark : targetEnd;

        List<Payment> targets = paymentRepository.findForPgReconciliation(from, to);
        if (targets.isEmpty()) {
            return 0;
        }
        Long jobExecutionId = findRunId(runDate, jobName);
        int mismatches = 0;
        for (Payment payment : targets) {
            try {
                mismatches += reconcileOne(payment, target, jobExecutionId, now);
            } catch (RuntimeException e) {
                meterRegistry.counter(RECON_METRIC, "result", "error").increment();
                log.error("event=BILLING_PG_RECON_FAILED paymentId={} businessDate={}", payment.getId(), target, e);
            }
        }
        if (mismatches > 0) {
            log.error("event=BILLING_PG_RECON_DONE businessDate={} paymentCount={} mismatchCount={}",
                    target, targets.size(), mismatches);
        } else {
            log.info("event=BILLING_PG_RECON_DONE businessDate={} paymentCount={} mismatchCount={}",
                    target, targets.size(), mismatches);
        }
        return mismatches;
    }

    private int reconcileOne(Payment payment, LocalDate businessDate, Long jobExecutionId, LocalDateTime now) {
        PaymentGatewayInquiryResult result = inquireWithRetry(payment.getPgTid());
        if (result == null) {
            meterRegistry.counter(RECON_METRIC, "result", "inquiry_failed").increment();
            return 0;
        }
        if (result.status() == Status.PENDING) {
            return 0;
        }
        long pgClaim = result.status() == Status.APPROVED ? result.amount() : 0L;
        long ourClaim = payment.getStatus() == PaymentStatus.APPROVED ? payment.getAmount() : 0L;
        if (pgClaim == ourClaim) {
            if (ourClaim > 0 && !ledgerRepository.existsByPaymentIdAndEntryType(payment.getId(), EntryType.CHARGE)) {
                mismatchRecorder.recordMissingCharge(payment, businessDate, jobExecutionId, now);
                log.warn("event=BILLING_PG_RECON_CHARGE_RECOVERED paymentId={} businessDate={} fallback=AUTO_RECOVER",
                        payment.getId(), businessDate);
                meterRegistry.counter(RECON_METRIC, "result", "recovered").increment();
                return 1;
            }
            return 0;
        }
        mismatchRecorder.recordMismatch(payment.getId(), pgClaim, ourClaim, businessDate, jobExecutionId, now);
        log.warn("event=BILLING_PG_RECON_MISMATCH paymentId={} expectedAmount={} actualAmount={}",
                payment.getId(), ourClaim, pgClaim);
        meterRegistry.counter(RECON_METRIC, "result", "mismatch").increment();
        return 1;
    }

    // 조회가 멱등(읽기 전용)이라 재시도해도 안전하다. 짧게 1회만 재시도하고 그래도 실패하면 포기한다
    private PaymentGatewayInquiryResult inquireWithRetry(String pgTid) {
        try {
            return paymentGateway.inquireByPaymentKey(pgTid);
        } catch (PgNotSentException | PgUnknownException first) {
            try {
                return paymentGateway.inquireByPaymentKey(pgTid);
            } catch (PgNotSentException | PgUnknownException second) {
                log.error("event=BILLING_PG_RECON_INQUIRY_FAILED pgTid={}", pgTid, second);
                return null;
            }
        }
    }

    // 작업 행은 스케줄러가 점유할 때 먼저 만든다. 찾지 못하면 NULL 로 남긴다(소프트 참조)
    private Long findRunId(LocalDate runDate, String jobName) {
        return batchExecutions.findByBusinessDate(runDate).stream()
                .filter(record -> record.jobName().equals(jobName))
                .map(BatchExecutionRecord::runId)
                .findFirst()
                .orElse(null);
    }
}
