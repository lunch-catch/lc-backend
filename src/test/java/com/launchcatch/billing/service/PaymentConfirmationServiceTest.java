package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.client.PaymentGateway;
import com.launchcatch.billing.client.PaymentGatewayApproval;
import com.launchcatch.billing.client.PaymentGatewayInquiryResult;
import com.launchcatch.billing.client.PaymentGatewayInquiryResult.Status;
import com.launchcatch.billing.client.PgNotSentException;
import com.launchcatch.billing.client.PgRejectedException;
import com.launchcatch.billing.client.PgUnknownException;
import com.launchcatch.billing.dto.DisplayStatus;
import com.launchcatch.billing.dto.PaymentConfirmResponse;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 승인 확정의 흐름을 본다. 결제대행사는 목으로 대신한다(UT-5-01).
 * 결과 기록은 잠금과 트랜잭션이 필요한 일이라 결과 기록 서비스 목으로 대신하고, 여기서는 어느 결과를 기록하라고 했는지와
 * 그 결과로 어떻게 답했는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentConfirmationServiceTest {

    private static final Long OWNER = 7L;
    private static final Long OTHER_OWNER = 8L;
    private static final Long PAYMENT_ID = 101L;
    private static final String ORDER_ID = "ord_20261011_abc";
    private static final String PG_TID = "tgen_20261011_0001";
    private static final long AMOUNT = 30000L;
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 11, 10, 0, 3);

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentResultService resultService;
    @Mock PaymentGateway paymentGateway;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private PaymentConfirmationService service() {
        return new PaymentConfirmationService(paymentRepository, resultService, paymentGateway, meterRegistry);
    }

    private Payment requested(Long ownerId) {
        Payment payment = Payment.order(ownerId, ORDER_ID, AMOUNT);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    private Payment attempted() {
        Payment payment = requested(OWNER);
        payment.assignPgTid(PG_TID);
        return payment;
    }

    private Payment approved() {
        Payment payment = attempted();
        payment.approve(APPROVED_AT);
        return payment;
    }

    private Payment failed() {
        Payment payment = attempted();
        payment.fail("REJECT_CARD_COMPANY: 카드사 거절");
        return payment;
    }

    private Payment unknown() {
        Payment payment = attempted();
        payment.markUnknown();
        return payment;
    }

    private Payment cancelled() {
        Payment payment = attempted();
        payment.cancel(APPROVED_AT, null);
        return payment;
    }

    private void stored(Payment payment) {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
    }

    // 승인 시도 전 주문이 있고 결제 식별자 기록까지 통과한 상태다
    private void readyToCall() {
        stored(requested(OWNER));
        when(resultService.assignPgTid(PAYMENT_ID, PG_TID)).thenReturn(attempted());
    }

    private void pgUnknown() {
        when(paymentGateway.confirm(PG_TID, ORDER_ID, AMOUNT))
                .thenThrow(new PgUnknownException("read timeout", new RuntimeException()));
    }

    private PaymentGatewayInquiryResult inquiry(Status status, String orderId, long amount) {
        return new PaymentGatewayInquiryResult(status, PG_TID, orderId, amount, APPROVED_AT, null, null);
    }

    private PaymentConfirmResponse confirm() {
        return service().confirm(OWNER, PAYMENT_ID, ORDER_ID, PG_TID);
    }

    private double counted(String result) {
        return meterRegistry.counter(PaymentConfirmationService.CONFIRM_METRIC, "result", result).count();
    }

    // --- 결제대행사를 부르기 전에 끝나는 경우 ------------------------------------------

    @Test
    void 없는_결제면_BILLING_004이고_결제대행사를_부르지_않는다() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_NOT_FOUND));
        verifyNoInteractions(paymentGateway, resultService);
    }

    @Test
    void 다른_점주의_결제면_있다는_것도_알리지_않고_BILLING_004다() {
        stored(requested(OTHER_OWNER));

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_NOT_FOUND));
        verifyNoInteractions(paymentGateway, resultService);
    }

    @Test
    void 주문_번호가_저장값과_다르면_BILLING_017이고_결제대행사를_부르지_않는다() {
        stored(requested(OWNER));

        assertThatThrownBy(() -> service().confirm(OWNER, PAYMENT_ID, "ord_forged", PG_TID))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.ORDER_ID_MISMATCH));
        verifyNoInteractions(paymentGateway, resultService);
    }

    @Test
    void 이미_승인된_결제는_다시_승인하지_않고_그대로_돌려준다() {
        stored(approved());

        PaymentConfirmResponse response = confirm();

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.chargedPoint()).isEqualTo(AMOUNT);
        verifyNoInteractions(paymentGateway, resultService);
    }

    @Test
    void 이미_실패한_결제는_BILLING_003이다() {
        stored(failed());

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_REJECTED));
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void 확인_중인_결제는_다시_부르지_않고_확인_중으로_답한다() {
        stored(unknown());

        PaymentConfirmResponse response = confirm();

        assertThat(response.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(response.displayStatus()).isEqualTo(DisplayStatus.CONFIRMING);
        assertThat(response.chargedPoint()).isNull();
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void 취소된_주문은_BILLING_009이고_새로_주문해야_한다() {
        stored(cancelled());

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_ALREADY_CANCELLED));
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void 결제_식별자가_다른_결제에_이미_쓰였으면_BILLING_017이고_결제대행사를_부르지_않는다() {
        stored(requested(OWNER));
        when(resultService.assignPgTid(PAYMENT_ID, PG_TID)).thenThrow(new DataIntegrityViolationException("dup",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'tgen_20261011_0001' for key 'payment.uk_payment_pg_tid'")));

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.ORDER_ID_MISMATCH));
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void 결제_식별자의_다른_제약_위반은_그대로_던진다() {
        stored(requested(OWNER));
        DataIntegrityViolationException other = new DataIntegrityViolationException("other",
                new SQLIntegrityConstraintViolationException("Check constraint 'chk_payment_pg_tid' is violated."));
        when(resultService.assignPgTid(PAYMENT_ID, PG_TID)).thenThrow(other);

        assertThatThrownBy(this::confirm).isSameAs(other);
    }

    @Test
    void 결제_식별자를_적는_사이에_다른_요청이_결과를_냈으면_그_결과로_답하고_결제대행사를_부르지_않는다() {
        stored(requested(OWNER));
        when(resultService.assignPgTid(PAYMENT_ID, PG_TID)).thenReturn(unknown());

        assertThat(confirm().status()).isEqualTo(PaymentStatus.UNKNOWN);
        verifyNoInteractions(paymentGateway);
    }

    // --- 승인 요청 결과 ----------------------------------------------------------------

    @Test
    void 승인되면_저장된_금액으로_요청하고_승인을_기록해_충전_포인트와_함께_답한다() {
        readyToCall();
        when(paymentGateway.confirm(PG_TID, ORDER_ID, AMOUNT))
                .thenReturn(new PaymentGatewayApproval(PG_TID, ORDER_ID, AMOUNT, APPROVED_AT));
        when(resultService.approve(PAYMENT_ID, APPROVED_AT)).thenReturn(approved());

        PaymentConfirmResponse response = confirm();

        assertThat(response.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.displayStatus()).isEqualTo(DisplayStatus.COMPLETED);
        assertThat(response.amount()).isEqualTo(AMOUNT);
        assertThat(response.chargedPoint()).isEqualTo(AMOUNT);
        assertThat(counted("approved")).isEqualTo(1.0);
    }

    @Test
    void 승인_응답의_금액이_저장값과_다르면_승인으로_보지_않고_확인_중으로_둔다() {
        readyToCall();
        when(paymentGateway.confirm(PG_TID, ORDER_ID, AMOUNT))
                .thenReturn(new PaymentGatewayApproval(PG_TID, ORDER_ID, 100L, APPROVED_AT));
        when(resultService.markUnknown(PAYMENT_ID)).thenReturn(unknown());

        assertThat(confirm().status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(resultService, never()).approve(anyLong(), any());
        assertThat(counted("unknown")).isEqualTo(1.0);
    }

    @Test
    void 승인_응답의_주문_번호가_다르면_승인으로_보지_않는다() {
        readyToCall();
        when(paymentGateway.confirm(PG_TID, ORDER_ID, AMOUNT))
                .thenReturn(new PaymentGatewayApproval(PG_TID, "ord_other", AMOUNT, APPROVED_AT));
        when(resultService.markUnknown(PAYMENT_ID)).thenReturn(unknown());

        assertThat(confirm().status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(resultService, never()).approve(anyLong(), any());
    }

    @Test
    void 거절되면_코드와_사유로_실패를_기록하고_BILLING_003이다() {
        readyToCall();
        when(paymentGateway.confirm(PG_TID, ORDER_ID, AMOUNT))
                .thenThrow(new PgRejectedException("REJECT_CARD_COMPANY", "카드사 거절"));
        when(resultService.fail(PAYMENT_ID, "REJECT_CARD_COMPANY: 카드사 거절")).thenReturn(failed());

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_REJECTED));
        assertThat(counted("failed")).isEqualTo(1.0);
    }

    @Test
    void 거절_사유가_비어_있으면_정해_둔_문구로_실패를_기록한다() {
        readyToCall();
        when(paymentGateway.confirm(PG_TID, ORDER_ID, AMOUNT)).thenThrow(new PgRejectedException(null, " "));
        when(resultService.fail(PAYMENT_ID, "결제대행사가 승인을 거절했습니다.")).thenReturn(failed());

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_REJECTED));
    }

    @Test
    void 거절을_받았어도_다른_요청이_먼저_승인했으면_승인으로_답한다() {
        readyToCall();
        when(paymentGateway.confirm(PG_TID, ORDER_ID, AMOUNT))
                .thenThrow(new PgRejectedException("REJECT_CARD_COMPANY", "카드사 거절"));
        when(resultService.fail(anyLong(), anyString())).thenReturn(approved());

        assertThat(confirm().status()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 요청이_가지_않았으면_상태를_바꾸지_않고_BILLING_007이다() {
        readyToCall();
        when(paymentGateway.confirm(PG_TID, ORDER_ID, AMOUNT))
                .thenThrow(new PgNotSentException("circuit open", new RuntimeException()));

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PG_REQUEST_NOT_SENT));
        verify(resultService, never()).markUnknown(anyLong());
        verify(resultService, never()).fail(anyLong(), anyString());
        assertThat(counted("not_sent")).isEqualTo(1.0);
    }

    // --- 응답이 불명일 때의 동기 재조회 ------------------------------------------------------

    @Test
    void 응답이_불명이면_저장한_결제_키로_다시_조회해_승인이면_승인을_기록한다() {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(inquiry(Status.APPROVED, ORDER_ID, AMOUNT));
        when(resultService.approve(PAYMENT_ID, APPROVED_AT)).thenReturn(approved());

        assertThat(confirm().status()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 재조회_결과가_승인이어도_금액이_다르면_확인_중으로_둔다() {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(inquiry(Status.APPROVED, ORDER_ID, 100L));
        when(resultService.markUnknown(PAYMENT_ID)).thenReturn(unknown());

        assertThat(confirm().status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(resultService, never()).approve(anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"FAILED", "EXPIRED", "CANCELED"})
    void 다른_주문의_종료_결과는_확인_중으로_둔다(Status status) {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(inquiry(status, "ord_other", AMOUNT));
        when(resultService.markUnknown(PAYMENT_ID)).thenReturn(unknown());

        assertThat(confirm().status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(resultService, never()).fail(anyLong(), anyString());
        verify(resultService, never()).cancel(anyLong());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"FAILED", "EXPIRED", "CANCELED"})
    void 금액이_다른_종료_결과는_확인_중으로_둔다(Status status) {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(inquiry(status, ORDER_ID, AMOUNT + 1));
        when(resultService.markUnknown(PAYMENT_ID)).thenReturn(unknown());

        assertThat(confirm().status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(resultService, never()).fail(anyLong(), anyString());
        verify(resultService, never()).cancel(anyLong());
    }

    @Test
    void 재조회_결과가_실패면_코드와_사유로_실패를_기록하고_BILLING_003이다() {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(new PaymentGatewayInquiryResult(
                Status.FAILED, PG_TID, ORDER_ID, AMOUNT, null, "PAY_PROCESS_ABORTED", "결제가 중단되었습니다"));
        when(resultService.fail(PAYMENT_ID, "PAY_PROCESS_ABORTED: 결제가 중단되었습니다")).thenReturn(failed());

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_REJECTED));
    }

    @Test
    void 재조회_실패_사유가_없으면_정해_둔_문구로_실패를_기록한다() {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(new PaymentGatewayInquiryResult(
                Status.FAILED, PG_TID, ORDER_ID, AMOUNT, null, null, null));
        when(resultService.fail(PAYMENT_ID, "결제대행사 조회 결과 결제가 실패했습니다.")).thenReturn(failed());

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_REJECTED));
    }

    @Test
    void 재조회로_만료가_확인되면_취소로_닫고_BILLING_003이다() {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(inquiry(Status.EXPIRED, ORDER_ID, AMOUNT));
        when(resultService.cancel(PAYMENT_ID)).thenReturn(cancelled());

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_REJECTED));
        assertThat(counted("failed")).isEqualTo(1.0);
    }

    @Test
    void 재조회로_결제대행사_쪽_취소가_확인되면_충전_없이_취소로_닫는다() {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(inquiry(Status.CANCELED, ORDER_ID, AMOUNT));
        when(resultService.cancel(PAYMENT_ID)).thenReturn(cancelled());

        assertThatThrownBy(this::confirm).isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_REJECTED));
        verify(resultService, never()).approve(anyLong(), any());
    }

    @Test
    void 재조회로도_정하지_못하면_확인_중으로_두고_202_응답감을_돌려준다() {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(inquiry(Status.PENDING, ORDER_ID, AMOUNT));
        when(resultService.markUnknown(PAYMENT_ID)).thenReturn(unknown());

        PaymentConfirmResponse response = confirm();

        assertThat(response.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(response.displayStatus()).isEqualTo(DisplayStatus.CONFIRMING);
        assertThat(counted("unknown")).isEqualTo(1.0);
    }

    // 승인 요청이 결제대행사에 닿지 않았을 수 있어 실패로 단정하지 않는다
    @Test
    void 재조회에서_결제가_없다고_해도_확인_중으로_둔다() {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID)).thenReturn(PaymentGatewayInquiryResult.notFound());
        when(resultService.markUnknown(PAYMENT_ID)).thenReturn(unknown());

        assertThat(confirm().status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void 재조회_자체가_실패하면_확인_중으로_둔다() {
        readyToCall();
        pgUnknown();
        when(paymentGateway.inquireByPaymentKey(PG_TID))
                .thenThrow(new PgUnknownException("timeout", new RuntimeException()));
        when(resultService.markUnknown(PAYMENT_ID)).thenReturn(unknown());

        assertThat(confirm().status()).isEqualTo(PaymentStatus.UNKNOWN);
    }
}
