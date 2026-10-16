package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * PG_VS_CHARGE 대사(13번)를 본다. PG 와 결제 저장소는 목으로 대신하고, 둘이 서로 다른 금액을
 * 승인으로 볼 때 어떤 기록을 시키는지와 조회·기록 실패가 다음 건을 막지 않는지를 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PgChargeReconciliationServiceTest {

    private static final String JOB = "BILLING_PG_VS_CHARGE_RECON";
    private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 16);
    private static final LocalDate PREV = LocalDate.of(2026, 10, 15);
    // 서버가 UTC 로 떠도 영업일과 기록 시각은 KST 다. 2026-10-15T15:00Z 는 KST 2026-10-16 00:00 이다
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T15:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 0, 0);
    private static final LocalDateTime FROM = PREV.atStartOfDay();
    // 워터마크(5분)가 전영업일 끝보다 앞이라 실제 상한은 이 값이다
    private static final LocalDateTime TO = NOW.minusMinutes(5);

    private static final Long PAYMENT_ID = 101L;
    private static final Long OWNER_ID = 7L;
    private static final String PG_TID = "tgen_20261015_0001";
    private static final long AMOUNT = 30_000L;
    private static final Long RUN_ID = 55L;

    @Mock PaymentRepository paymentRepository;
    @Mock PointLedgerRepository ledgerRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock PgChargeMismatchRecorder mismatchRecorder;
    @Mock BatchExecutionQueryService batchExecutions;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private PgChargeReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new PgChargeReconciliationService(paymentRepository, ledgerRepository, paymentGateway,
                mismatchRecorder, batchExecutions, meterRegistry, CLOCK);
    }

    private static Payment payment(PaymentStatus status, long amount) {
        return payment(PAYMENT_ID, PG_TID, status, amount);
    }

    // pgTid 는 한 번 적으면 다른 값으로 못 바꾸므로(assignPgTid), 결제마다 다른 pgTid 가 필요하면 이 생성자로 만든다
    private static Payment payment(Long id, String pgTid, PaymentStatus status, long amount) {
        Payment payment = Payment.order(OWNER_ID, "ord_" + pgTid, amount);
        ReflectionTestUtils.setField(payment, "id", id);
        payment.assignPgTid(pgTid);
        switch (status) {
            case APPROVED -> payment.approve(LocalDateTime.of(2026, 10, 15, 10, 0));
            case FAILED -> payment.fail("REJECT_CARD_COMPANY");
            case CANCELLED -> payment.cancel(LocalDateTime.of(2026, 10, 15, 10, 0), "cancel-1");
            case UNKNOWN -> payment.markUnknown();
            case REQUESTED -> { /* 기본 상태 */ }
        }
        return payment;
    }

    private static PaymentGatewayInquiryResult pgResult(Status status, long amount) {
        return new PaymentGatewayInquiryResult(status, PG_TID, "ord_20261015_abc", amount,
                LocalDateTime.of(2026, 10, 15, 10, 0), null, null);
    }

    private void givenTargets(Payment... payments) {
        when(paymentRepository.findForPgReconciliation(FROM, TO)).thenReturn(List.of(payments));
    }

    private void givenRunId() {
        when(batchExecutions.findByBusinessDate(RUN_DATE)).thenReturn(
                List.of(new BatchExecutionRecord(RUN_ID, JOB, RUN_DATE, "RUNNING", NOW, null, null, "batch-1", 0,
                        null)));
    }

    private double counted(String result) {
        return meterRegistry.counter(PgChargeReconciliationService.RECON_METRIC, "result", result).count();
    }

    @Test
    void 대상이_없으면_아무것도_읽지_않는다() {
        when(paymentRepository.findForPgReconciliation(FROM, TO)).thenReturn(List.of());

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isZero();

        verifyNoInteractions(paymentGateway, mismatchRecorder, batchExecutions);
    }

    @Test
    void 전영업일_KST_범위로_찾는다() {
        when(paymentRepository.findForPgReconciliation(FROM, TO)).thenReturn(List.of());

        service.reconcilePreviousDay(RUN_DATE, JOB);

        verify(paymentRepository).findForPgReconciliation(FROM, TO);
    }

    @Test
    void PG_승인과_우리_CHARGE가_일치하면_기록하지_않는다() {
        Payment payment = payment(PaymentStatus.APPROVED, AMOUNT);
        givenTargets(payment);
        givenRunId();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(pgResult(Status.APPROVED, AMOUNT));
        when(ledgerRepository.existsByPaymentIdAndEntryType(PAYMENT_ID, EntryType.CHARGE)).thenReturn(true);

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isZero();

        verifyNoInteractions(mismatchRecorder);
    }

    @Test
    void PG_승인인데_CHARGE가_없으면_자동으로_보정한다() {
        Payment payment = payment(PaymentStatus.APPROVED, AMOUNT);
        givenTargets(payment);
        givenRunId();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(pgResult(Status.APPROVED, AMOUNT));
        when(ledgerRepository.existsByPaymentIdAndEntryType(PAYMENT_ID, EntryType.CHARGE)).thenReturn(false);

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(1);

        verify(mismatchRecorder).recordMissingCharge(payment, PREV, RUN_ID, NOW);
        verify(mismatchRecorder, never()).recordMismatch(any(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void PG_승인_금액이_다르면_자동_보정_없이_불일치만_남긴다() {
        Payment payment = payment(PaymentStatus.APPROVED, AMOUNT);
        givenTargets(payment);
        givenRunId();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(pgResult(Status.APPROVED, AMOUNT - 1_000));

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(1);

        verify(mismatchRecorder).recordMismatch(PAYMENT_ID, AMOUNT - 1_000, AMOUNT, PREV, RUN_ID, NOW);
        verify(mismatchRecorder, never()).recordMissingCharge(any(), any(), any(), any());
        verifyNoInteractions(ledgerRepository);
    }

    @Test
    void PG는_승인인데_우리_상태가_APPROVED가_아니면_CHARGE를_넣지_않고_불일치만_남긴다() {
        Payment payment = payment(PaymentStatus.UNKNOWN, AMOUNT);
        givenTargets(payment);
        givenRunId();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(pgResult(Status.APPROVED, AMOUNT));

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(1);

        verify(mismatchRecorder).recordMismatch(PAYMENT_ID, AMOUNT, 0L, PREV, RUN_ID, NOW);
        verify(mismatchRecorder, never()).recordMissingCharge(any(), any(), any(), any());
        verifyNoInteractions(ledgerRepository);
    }

    @Test
    void PG는_미승인인데_우리는_APPROVED이면_불일치만_남긴다() {
        Payment payment = payment(PaymentStatus.APPROVED, AMOUNT);
        givenTargets(payment);
        givenRunId();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(pgResult(Status.CANCELED, AMOUNT));

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(1);

        verify(mismatchRecorder).recordMismatch(PAYMENT_ID, 0L, AMOUNT, PREV, RUN_ID, NOW);
    }

    @Test
    void PG_PENDING이면_건너뛴다() {
        Payment payment = payment(PaymentStatus.UNKNOWN, AMOUNT);
        givenTargets(payment);
        givenRunId();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(pgResult(Status.PENDING, 0));

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isZero();

        verifyNoInteractions(mismatchRecorder, ledgerRepository);
    }

    @Test
    void 조회가_한번_실패해도_재시도해서_성공하면_정상_처리한다() {
        Payment payment = payment(PaymentStatus.APPROVED, AMOUNT);
        givenTargets(payment);
        givenRunId();
        when(paymentGateway.inquireByPaymentKey(PG_TID))
                .thenThrow(new PgUnknownException("read timeout", new RuntimeException()))
                .thenReturn(pgResult(Status.APPROVED, AMOUNT));
        when(ledgerRepository.existsByPaymentIdAndEntryType(PAYMENT_ID, EntryType.CHARGE)).thenReturn(false);

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(1);

        verify(paymentGateway, times(2)).inquireByPaymentKey(PG_TID);
        verify(mismatchRecorder).recordMissingCharge(payment, PREV, RUN_ID, NOW);
        assertThat(counted("inquiry_failed")).isZero();
    }

    @Test
    void 조회가_재시도까지_실패하면_기록_없이_지표만_남긴다() {
        Payment payment = payment(PaymentStatus.APPROVED, AMOUNT);
        givenTargets(payment);
        givenRunId();
        when(paymentGateway.inquireByPaymentKey(PG_TID))
                .thenThrow(new PgNotSentException("circuit open", new RuntimeException()));

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isZero();

        verify(paymentGateway, times(2)).inquireByPaymentKey(PG_TID);
        verifyNoInteractions(mismatchRecorder, ledgerRepository);
        assertThat(counted("inquiry_failed")).isEqualTo(1.0);
    }

    @Test
    void 한_건의_조회_실패가_나머지_결제_처리를_막지_않는다() {
        Payment failing = payment(201L, "tgen_fail", PaymentStatus.APPROVED, AMOUNT);
        Payment ok = payment(PaymentStatus.APPROVED, AMOUNT);
        givenTargets(failing, ok);
        givenRunId();
        when(paymentGateway.inquireByPaymentKey("tgen_fail"))
                .thenThrow(new PgNotSentException("down", new RuntimeException()));
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(pgResult(Status.APPROVED, AMOUNT - 1));

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(1);

        assertThat(counted("inquiry_failed")).isEqualTo(1.0);
        verify(mismatchRecorder).recordMismatch(PAYMENT_ID, AMOUNT - 1, AMOUNT, PREV, RUN_ID, NOW);
    }

    @Test
    void 기록_쓰기가_실패해도_오류로_세고_나머지를_계속한다() {
        Payment failing = payment(201L, "tgen_other", PaymentStatus.APPROVED, AMOUNT);
        Payment ok = payment(PaymentStatus.APPROVED, AMOUNT);
        givenTargets(failing, ok);
        givenRunId();
        when(paymentGateway.inquireByPaymentKey("tgen_other")).thenReturn(pgResult(Status.APPROVED, AMOUNT - 1));
        doThrow(new IllegalStateException("db down")).when(mismatchRecorder)
                .recordMismatch(eq(201L), anyLong(), anyLong(), eq(PREV), eq(RUN_ID), eq(NOW));
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(pgResult(Status.APPROVED, AMOUNT));
        when(ledgerRepository.existsByPaymentIdAndEntryType(PAYMENT_ID, EntryType.CHARGE)).thenReturn(false);

        assertThat(service.reconcilePreviousDay(RUN_DATE, JOB)).isEqualTo(1);

        assertThat(counted("error")).isEqualTo(1.0);
        verify(mismatchRecorder).recordMissingCharge(ok, PREV, RUN_ID, NOW);
    }
}
