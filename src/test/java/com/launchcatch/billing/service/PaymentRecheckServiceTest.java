package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.launchcatch.billing.dto.PaymentRecheckVerdict;
import com.launchcatch.billing.dto.PaymentRecheckVerdict.Decision;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 재조회 흐름을 본다. 결제대행사와 결과 기록 서비스는 목으로 대신하고,
 * 조회 결과별로 어떤 기록을 시켰는지와 확정하지 못한 경우를 닫지 않는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentRecheckServiceTest {

    private static final Long PAYMENT_ID = 101L;
    private static final String ORDER_ID = "ord_20261011_abc";
    private static final String PG_TID = "tgen_20261011_0001";
    private static final long AMOUNT = 30000L;
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 11, 10, 0, 3);

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentRecheckClaimService claimService;
    @Mock PaymentResultService resultService;
    @Mock PaymentGateway paymentGateway;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private PaymentRecheckService service() {
        return new PaymentRecheckService(paymentRepository, claimService, resultService, paymentGateway,
                meterRegistry);
    }

    private Payment unknownPayment() {
        Payment payment = Payment.order(7L, ORDER_ID, AMOUNT);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        payment.assignPgTid(PG_TID);
        payment.markUnknown();
        return payment;
    }

    private Payment unknown() {
        Payment payment = unknownPayment();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        return payment;
    }

    private void inquiryReturns(Status status, String orderId, long amount) {
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(
                new PaymentGatewayInquiryResult(status, PG_TID, orderId, amount, APPROVED_AT, null, null));
    }

    private double counted(String result) {
        return meterRegistry.counter(PaymentRecheckService.RECHECK_METRIC, "result", result).count();
    }

    @Test
    void 승인이_확인되면_충전을_기록한다() {
        unknown();
        inquiryReturns(Status.APPROVED, ORDER_ID, AMOUNT);

        service().recheck(PAYMENT_ID);

        verify(resultService).approve(PAYMENT_ID, APPROVED_AT);
        assertThat(counted("approve")).isEqualTo(1.0);
    }

    @Test
    void 실패가_확인되면_사유와_함께_실패로_닫는다() {
        unknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(new PaymentGatewayInquiryResult(
                Status.FAILED, PG_TID, ORDER_ID, AMOUNT, null, "REJECT_CARD_COMPANY", "카드사 거절"));

        service().recheck(PAYMENT_ID);

        verify(resultService).fail(PAYMENT_ID, "REJECT_CARD_COMPANY: 카드사 거절");
        assertThat(counted("fail")).isEqualTo(1.0);
    }

    @Test
    void 실패_사유가_비어_있으면_기본_문구로_닫는다() {
        unknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(new PaymentGatewayInquiryResult(
                Status.FAILED, PG_TID, ORDER_ID, AMOUNT, null, null, " "));

        service().recheck(PAYMENT_ID);

        verify(resultService).fail(PAYMENT_ID, "결제대행사 조회 결과 결제가 실패했습니다.");
    }

    @Test
    void 만료와_취소는_충전_없이_취소로_닫는다() {
        unknown();
        inquiryReturns(Status.EXPIRED, ORDER_ID, AMOUNT);
        service().recheck(PAYMENT_ID);
        inquiryReturns(Status.CANCELED, ORDER_ID, AMOUNT);
        service().recheck(PAYMENT_ID);

        verify(resultService, times(2)).cancel(PAYMENT_ID);
        verify(resultService, never()).approve(anyLong(), any());
        assertThat(counted("cancel")).isEqualTo(2.0);
    }

    @Test
    void 처리_중이면_닫지_않고_시도만_기록한다() {
        unknown();
        inquiryReturns(Status.PENDING, ORDER_ID, AMOUNT);

        service().recheck(PAYMENT_ID);

        verify(resultService).recordRecheckMiss(PAYMENT_ID, "결제대행사가 아직 처리 중입니다.");
        verify(resultService, never()).fail(anyLong(), anyString());
        verify(resultService, never()).cancel(anyLong());
        assertThat(counted("miss")).isEqualTo(1.0);
    }

    @Test
    void 결제가_없다고_해도_닫지_않고_시도만_기록한다() {
        unknown();
        inquiryReturns(Status.NOT_FOUND, null, 0L);

        service().recheck(PAYMENT_ID);

        verify(resultService).recordRecheckMiss(PAYMENT_ID, "결제대행사에 결제가 없습니다.");
        verify(resultService, never()).cancel(anyLong());
    }

    @Test
    void 조회가_실패하면_닫지_않고_시도만_기록한다() {
        unknown();
        doThrow(new PgUnknownException("read timeout", new RuntimeException()))
                .when(paymentGateway).inquireByPaymentKey(PG_TID);
        service().recheck(PAYMENT_ID);
        doThrow(new PgNotSentException("connect", new RuntimeException()))
                .when(paymentGateway).inquireByPaymentKey(PG_TID);
        service().recheck(PAYMENT_ID);

        verify(resultService, times(2))
                .recordRecheckMiss(PAYMENT_ID, "결제대행사 조회에 실패했습니다.");
    }

    @Test
    void 주문_번호나_금액이_다르면_승인하지_않고_시도만_기록한다() {
        unknown();
        inquiryReturns(Status.APPROVED, ORDER_ID, AMOUNT + 1);
        service().recheck(PAYMENT_ID);
        inquiryReturns(Status.APPROVED, "ord_other", AMOUNT);
        service().recheck(PAYMENT_ID);

        verify(resultService, never()).approve(anyLong(), any());
        verify(resultService, times(2))
                .recordRecheckMiss(PAYMENT_ID, "조회 결과의 주문 번호나 금액이 저장값과 다릅니다.");
    }

    @Test
    void 이미_확정된_결제는_결제대행사를_부르지_않는다() {
        Payment payment = unknown();
        payment.approve(APPROVED_AT);

        Payment result = service().recheck(PAYMENT_ID);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verifyNoInteractions(paymentGateway, resultService);
    }

    @Test
    void 없는_결제면_BILLING_004다() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().recheck(PAYMENT_ID))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    void 판정은_결제를_바꾸지_않고_결제대행사에만_묻는다() {
        Payment payment = unknownPayment();
        inquiryReturns(Status.APPROVED, ORDER_ID, AMOUNT);

        PaymentRecheckVerdict verdict = service().judge(payment);

        assertThat(verdict.decision()).isEqualTo(Decision.APPROVE);
        assertThat(verdict.approvedAt()).isEqualTo(APPROVED_AT);
        verifyNoInteractions(resultService);
    }

    @Test
    void 선점한_건을_모두_처리하고_건수를_돌려준다() {
        unknown();
        inquiryReturns(Status.PENDING, ORDER_ID, AMOUNT);
        when(claimService.claimDue()).thenReturn(List.of(PAYMENT_ID));

        assertThat(service().recheckDue()).isEqualTo(1);

        verify(resultService).recordRecheckMiss(anyLong(), anyString());
    }

    @Test
    void 한_건이_예외로_실패해도_다음_건을_계속한다() {
        when(claimService.claimDue()).thenReturn(List.of(999L, PAYMENT_ID));
        when(paymentRepository.findById(999L)).thenReturn(Optional.empty());
        unknown();
        inquiryReturns(Status.APPROVED, ORDER_ID, AMOUNT);

        assertThat(service().recheckDue()).isEqualTo(2);

        verify(resultService).approve(PAYMENT_ID, APPROVED_AT);
        assertThat(counted("error")).isEqualTo(1.0);
    }

    @Test
    void 기록이_예외로_실패해도_오류로_세고_끝까지_돈다() {
        unknown();
        inquiryReturns(Status.APPROVED, ORDER_ID, AMOUNT);
        doThrow(new IllegalStateException("db down")).when(resultService).approve(PAYMENT_ID, APPROVED_AT);
        when(claimService.claimDue()).thenReturn(List.of(PAYMENT_ID));

        assertThat(service().recheckDue()).isEqualTo(1);
        assertThat(counted("error")).isEqualTo(1.0);
    }
}
