package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.config.PaymentRecheckProperties;
import com.launchcatch.billing.dto.PointCharged;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.LedgerSource;
import com.launchcatch.billing.entity.OutboxStatus;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PaymentReconciliationOutbox;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PaymentReconciliationOutboxRepository;
import com.launchcatch.billing.repository.PaymentRepository;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentResultServiceTest {

    private static final Long OWNER = 7L;
    private static final Long PAYMENT_ID = 101L;
    private static final String ORDER_ID = "ord_20261011_abc";
    private static final String PG_TID = "tgen_20261011_0001";
    private static final long AMOUNT = 30000L;

    // 2026-10-11 10:00 KST
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T01:00:00Z"), ClockConfig.ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 10, 0);
    private static final LocalDateTime PG_APPROVED_AT = LocalDateTime.of(2026, 10, 11, 9, 59, 58);

    @Mock PaymentRepository paymentRepository;
    @Mock PointLedgerRepository pointLedgerRepository;
    @Mock PointAccountRepository pointAccountRepository;
    @Mock PaymentReconciliationOutboxRepository outboxRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    private PaymentResultService service() {
        return new PaymentResultService(paymentRepository, pointLedgerRepository, pointAccountRepository,
                outboxRepository, new PaymentRecheckProperties(10, Duration.ofMinutes(1)), eventPublisher, CLOCK);
    }

    private Payment requested() {
        Payment payment = Payment.order(OWNER, ORDER_ID, AMOUNT);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    private Payment attempted() {
        Payment payment = requested();
        payment.assignPgTid(PG_TID);
        return payment;
    }

    private Payment locked(Payment payment) {
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        return payment;
    }

    @Test
    void 결제_식별자를_적고_바로_flush_해서_유일_제약_위반을_이_자리에서_드러낸다() {
        Payment payment = locked(requested());

        Payment result = service().assignPgTid(PAYMENT_ID, PG_TID);

        assertThat(result.getPgTid()).isEqualTo(PG_TID);
        verify(paymentRepository).flush();
    }

    @Test
    void 잠근_뒤_이미_승인_시도_전이_아니면_결제_식별자를_적지_않는다() {
        Payment payment = attempted();
        payment.markUnknown();
        locked(payment);

        Payment result = service().assignPgTid(PAYMENT_ID, "tgen_other");

        assertThat(result.getPgTid()).isEqualTo(PG_TID);
        verify(paymentRepository, never()).flush();
    }

    @Test
    void 승인_결과를_기다리는_결제의_식별자가_다르면_BILLING_017이고_기존_값을_유지한다() {
        Payment payment = locked(attempted());

        assertThatThrownBy(() -> service().assignPgTid(PAYMENT_ID, "tgen_other"))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.ORDER_ID_MISMATCH));
        assertThat(payment.getPgTid()).isEqualTo(PG_TID);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 승인_결과를_기다리는_결제에_같은_식별자를_다시_적을_수_있다() {
        locked(attempted());

        Payment result = service().assignPgTid(PAYMENT_ID, PG_TID);

        assertThat(result.getPgTid()).isEqualTo(PG_TID);
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void 없는_결제면_BILLING_004다() {
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().approve(PAYMENT_ID, PG_APPROVED_AT))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    void 승인하면_결제를_APPROVED로_바꾸고_충전_한_줄과_점주_계정과_충전_통보를_남긴다() {
        locked(attempted());
        when(outboxRepository.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());

        Payment result = service().approve(PAYMENT_ID, PG_APPROVED_AT);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(result.getApprovedAt()).isEqualTo(PG_APPROVED_AT);
        ArgumentCaptor<PointLedger> ledger = ArgumentCaptor.forClass(PointLedger.class);
        verify(pointLedgerRepository).save(ledger.capture());
        assertThat(ledger.getValue().getEntryType()).isEqualTo(EntryType.CHARGE);
        assertThat(ledger.getValue().getAmount()).isEqualTo(AMOUNT);
        assertThat(ledger.getValue().getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(ledger.getValue().getOwnerId()).isEqualTo(OWNER);
        assertThat(ledger.getValue().getSource()).isEqualTo(LedgerSource.REALTIME);
        verify(pointAccountRepository).createIfAbsent(OWNER, NOW);
        verify(eventPublisher).publishEvent(new PointCharged(OWNER, PAYMENT_ID, AMOUNT));
    }

    @Test
    void 승인_시각이_비어_있으면_지금_시각으로_승인한다() {
        locked(attempted());
        when(outboxRepository.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());

        assertThat(service().approve(PAYMENT_ID, null).getApprovedAt()).isEqualTo(NOW);
    }

    @Test
    void 확인_중이던_결제가_승인되면_재조회_아웃박스를_닫는다() {
        Payment payment = attempted();
        payment.markUnknown();
        locked(payment);
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 10, NOW);
        when(outboxRepository.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(outbox));

        service().approve(PAYMENT_ID, PG_APPROVED_AT);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.RESOLVED);
        assertThat(outbox.getLastCheckedAt()).isEqualTo(NOW);
    }

    @Test
    void 이미_승인된_결제는_다시_충전하지_않고_통보도_내지_않는다() {
        Payment payment = attempted();
        payment.approve(PG_APPROVED_AT);
        locked(payment);

        Payment result = service().approve(PAYMENT_ID, PG_APPROVED_AT.plusMinutes(1));

        assertThat(result.getApprovedAt()).isEqualTo(PG_APPROVED_AT);
        verifyNoInteractions(pointLedgerRepository, pointAccountRepository, eventPublisher);
    }

    @Test
    void 이미_승인된_결제에_뒤늦은_거절이_와도_실패로_덮지_않는다() {
        Payment payment = attempted();
        payment.approve(PG_APPROVED_AT);
        locked(payment);

        Payment result = service().fail(PAYMENT_ID, "ALREADY_PROCESSED_PAYMENT: 이미 처리된 결제입니다");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(result.getFailureReason()).isNull();
    }

    @Test
    void 이미_실패한_결제는_승인으로_바꾸지_않는다() {
        Payment payment = attempted();
        payment.fail("REJECT_CARD_COMPANY: 카드사 거절");
        locked(payment);

        Payment result = service().approve(PAYMENT_ID, PG_APPROVED_AT);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verifyNoInteractions(pointLedgerRepository, eventPublisher);
    }

    @Test
    void 거절되면_사유와_함께_FAILED로_바꾸고_충전하지_않는다() {
        locked(attempted());
        when(outboxRepository.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());

        Payment result = service().fail(PAYMENT_ID, "REJECT_CARD_COMPANY: 카드사 거절");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.getFailureReason()).isEqualTo("REJECT_CARD_COMPANY: 카드사 거절");
        verifyNoInteractions(pointLedgerRepository, eventPublisher);
    }

    @Test
    void 만료가_확인되면_취소_식별자_없이_CANCELLED로_닫는다() {
        locked(attempted());
        when(outboxRepository.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());

        Payment result = service().cancel(PAYMENT_ID);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(result.getCanceledAt()).isEqualTo(NOW);
        assertThat(result.getPgCancelId()).isNull();
        verifyNoInteractions(pointLedgerRepository, eventPublisher);
    }

    @Test
    void 이미_승인된_결제는_취소로_바꾸지_않는다() {
        Payment payment = attempted();
        payment.approve(PG_APPROVED_AT);
        locked(payment);

        assertThat(service().cancel(PAYMENT_ID).getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 결과를_모르면_UNKNOWN으로_두고_설정한_상한과_간격으로_재조회_아웃박스를_만든다() {
        locked(attempted());

        Payment result = service().markUnknown(PAYMENT_ID);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        ArgumentCaptor<PaymentReconciliationOutbox> outbox =
                ArgumentCaptor.forClass(PaymentReconciliationOutbox.class);
        verify(outboxRepository).save(outbox.capture());
        assertThat(outbox.getValue().getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(outbox.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getValue().getMaxRetry()).isEqualTo(10);
        assertThat(outbox.getValue().getNextRetryAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    void 이미_확인_중인_결제는_아웃박스를_또_만들지_않는다() {
        Payment payment = attempted();
        payment.markUnknown();
        locked(payment);

        service().markUnknown(PAYMENT_ID);

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void 이미_승인된_결제는_확인_중으로_되돌리지_않는다() {
        Payment payment = attempted();
        payment.approve(PG_APPROVED_AT);
        locked(payment);

        assertThat(service().markUnknown(PAYMENT_ID).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(outboxRepository, never()).save(any());
    }
}
