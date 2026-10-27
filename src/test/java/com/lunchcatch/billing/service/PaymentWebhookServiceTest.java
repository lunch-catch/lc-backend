package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lunchcatch.billing.cache.WebhookInquiryGuard;
import com.lunchcatch.billing.client.PaymentGateway;
import com.lunchcatch.billing.client.PaymentGatewayInquiryResult;
import com.lunchcatch.billing.client.PaymentGatewayInquiryResult.Status;
import com.lunchcatch.billing.client.PgUnknownException;
import com.lunchcatch.billing.dto.PaymentWebhookRequest;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 웹훅 처리를 본다. 결제대행사와 결과 기록 서비스는 목으로 대신하고,
 * 본문을 믿지 않고 조회로 확정하는지, 처리할 수 없는 요청을 조용히 끝내는지, 조회 실패를 밖으로 던지는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

    private static final Long PAYMENT_ID = 101L;
    private static final String ORDER_ID = "ord_20261011_abc";
    private static final String PAYMENT_KEY = "tgen_20261011_0001";
    private static final long AMOUNT = 30000L;
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 11, 10, 0, 3);

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentResultService resultService;
    @Mock PaymentGateway paymentGateway;
    @Mock WebhookInquiryGuard inquiryGuard;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void openGuard() {
        lenient().when(inquiryGuard.tryAcquire(anyString())).thenReturn(true);
    }

    private PaymentWebhookService service() {
        return new PaymentWebhookService(paymentRepository, resultService, paymentGateway, inquiryGuard,
                meterRegistry);
    }

    private PaymentWebhookRequest request() {
        return new PaymentWebhookRequest("PAYMENT_STATUS_CHANGED",
                new PaymentWebhookRequest.Data(PAYMENT_KEY, ORDER_ID));
    }

    private Payment requested() {
        Payment payment = Payment.order(7L, ORDER_ID, AMOUNT);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    private Payment attempted() {
        Payment payment = requested();
        payment.assignPgTid(PAYMENT_KEY);
        return payment;
    }

    private Payment unknown() {
        Payment payment = attempted();
        payment.markUnknown();
        return payment;
    }

    private Payment approved() {
        Payment payment = attempted();
        payment.approve(APPROVED_AT);
        return payment;
    }

    private void stored(Payment payment) {
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
    }

    private void inquiry(Status status) {
        when(paymentGateway.inquireByPaymentKey(PAYMENT_KEY)).thenReturn(new PaymentGatewayInquiryResult(
                status, PAYMENT_KEY, ORDER_ID, AMOUNT, APPROVED_AT, "REJECT_CARD_COMPANY", "카드사 거절"));
    }

    private double counted(String result) {
        return meterRegistry.counter(PaymentWebhookService.WEBHOOK_METRIC, "result", result).count();
    }

    @Test
    void 본문이_비어_있거나_형식이_틀리면_아무것도_부르지_않고_끝낸다() {
        service().handle(null);
        service().handle(new PaymentWebhookRequest("OTHER_EVENT", new PaymentWebhookRequest.Data(PAYMENT_KEY, ORDER_ID)));
        service().handle(new PaymentWebhookRequest("PAYMENT_STATUS_CHANGED", null));
        service().handle(new PaymentWebhookRequest("PAYMENT_STATUS_CHANGED", new PaymentWebhookRequest.Data(null, ORDER_ID)));
        service().handle(new PaymentWebhookRequest("PAYMENT_STATUS_CHANGED", new PaymentWebhookRequest.Data(" ", ORDER_ID)));
        service().handle(new PaymentWebhookRequest("PAYMENT_STATUS_CHANGED", new PaymentWebhookRequest.Data(PAYMENT_KEY, null)));
        service().handle(new PaymentWebhookRequest("PAYMENT_STATUS_CHANGED", new PaymentWebhookRequest.Data(PAYMENT_KEY, "")));

        verifyNoInteractions(paymentRepository, paymentGateway, resultService);
        assertThat(counted("ignored")).isEqualTo(7.0);
    }

    @Test
    void 모르는_주문이면_결제대행사를_부르지_않고_끝낸다() {
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        service().handle(request());

        verifyNoInteractions(paymentGateway, resultService);
        assertThat(counted("ignored")).isEqualTo(1.0);
    }

    @Test
    void 모르는_주문이면_접두사가_붙은_이벤트에_대문자_사유를_남긴다() {
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentWebhookService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        try {
            service().handle(request());
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage())
                        .isEqualTo("event=BILLING_PAYMENT_WEBHOOK_SKIPPED reason=UNKNOWN_ORDER orderId=" + ORDER_ID));
    }

    @Test
    void 조회_결과의_금액이나_주문_번호가_다르면_무시한다() {
        stored(unknown());
        when(paymentGateway.inquireByPaymentKey(PAYMENT_KEY)).thenReturn(new PaymentGatewayInquiryResult(
                Status.APPROVED, PAYMENT_KEY, ORDER_ID, AMOUNT + 1, APPROVED_AT, null, null));
        service().handle(request());
        when(paymentGateway.inquireByPaymentKey(PAYMENT_KEY)).thenReturn(new PaymentGatewayInquiryResult(
                Status.APPROVED, PAYMENT_KEY, "ord_other", AMOUNT, APPROVED_AT, null, null));
        service().handle(request());

        verifyNoInteractions(resultService);
        assertThat(counted("ignored")).isEqualTo(2.0);
    }

    @Test
    void 결제대행사에_결제가_없으면_무시한다() {
        stored(unknown());
        when(paymentGateway.inquireByPaymentKey(PAYMENT_KEY)).thenReturn(PaymentGatewayInquiryResult.notFound());

        service().handle(request());

        verifyNoInteractions(resultService);
    }

    @Test
    void 확인_중인_결제가_승인으로_조회되면_충전한다() {
        stored(unknown());
        inquiry(Status.APPROVED);

        service().handle(request());

        verify(resultService).approve(PAYMENT_ID, APPROVED_AT);
        verify(resultService, never()).assignPgTid(anyLong(), anyString());
        assertThat(counted("approved")).isEqualTo(1.0);
    }

    @Test
    void 승인_시도_전_주문이_승인으로_조회되면_결제_식별자를_먼저_적고_충전한다() {
        stored(requested());
        inquiry(Status.APPROVED);

        service().handle(request());

        InOrder order = inOrder(resultService);
        order.verify(resultService).assignPgTid(PAYMENT_ID, PAYMENT_KEY);
        order.verify(resultService).approve(PAYMENT_ID, APPROVED_AT);
    }

    @Test
    void 다른_식별자가_이미_적힌_주문이면_충전하지_않고_끝낸다() {
        stored(requested());
        inquiry(Status.APPROVED);
        when(resultService.assignPgTid(PAYMENT_ID, PAYMENT_KEY))
                .thenThrow(new BillingException(BillingErrorCode.ORDER_ID_MISMATCH));

        service().handle(request());

        verify(resultService, never()).approve(anyLong(), any());
        assertThat(counted("ignored")).isEqualTo(1.0);
    }

    @Test
    void 결제_식별자_기록이_다른_이유의_업무_오류면_그대로_던진다() {
        stored(requested());
        inquiry(Status.APPROVED);
        when(resultService.assignPgTid(PAYMENT_ID, PAYMENT_KEY))
                .thenThrow(new BillingException(BillingErrorCode.PAYMENT_NOT_FOUND));

        assertThatThrownBy(() -> service().handle(request())).isInstanceOf(BillingException.class);
    }

    @Test
    void 다른_결제가_이미_쓰는_식별자면_충전하지_않고_끝낸다() {
        stored(requested());
        inquiry(Status.APPROVED);
        when(resultService.assignPgTid(PAYMENT_ID, PAYMENT_KEY)).thenThrow(new DataIntegrityViolationException(
                "dup", new SQLException("Duplicate entry for key 'payment.uk_payment_pg_tid'", "23000", 1062)));

        service().handle(request());

        verify(resultService, never()).approve(anyLong(), any());
    }

    @Test
    void 결제_식별자_기록이_다른_제약_위반이면_그대로_던진다() {
        stored(requested());
        inquiry(Status.APPROVED);
        when(resultService.assignPgTid(PAYMENT_ID, PAYMENT_KEY)).thenThrow(new DataIntegrityViolationException(
                "other", new SQLException("Duplicate entry for key 'payment.other'", "23000", 1062)));

        assertThatThrownBy(() -> service().handle(request())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 실패로_조회되면_사유와_함께_실패로_닫는다() {
        stored(unknown());
        inquiry(Status.FAILED);

        service().handle(request());

        verify(resultService).fail(PAYMENT_ID, "REJECT_CARD_COMPANY: 카드사 거절");
        assertThat(counted("failed")).isEqualTo(1.0);
    }

    @Test
    void 실패_사유가_없으면_기본_문구로_닫는다() {
        stored(unknown());
        when(paymentGateway.inquireByPaymentKey(PAYMENT_KEY)).thenReturn(new PaymentGatewayInquiryResult(
                Status.FAILED, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null, null));

        service().handle(request());

        verify(resultService).fail(PAYMENT_ID, "결제대행사 조회 결과 결제가 실패했습니다.");
    }

    @Test
    void 승인_시도_전_주문의_실패는_결제_식별자가_없어_닫지_않고_무시한다() {
        stored(requested());
        inquiry(Status.FAILED);

        service().handle(request());

        verifyNoInteractions(resultService);
        assertThat(counted("ignored")).isEqualTo(1.0);
    }

    @Test
    void 만료로_조회되면_충전_없이_취소로_닫는다() {
        stored(requested());
        inquiry(Status.EXPIRED);

        service().handle(request());

        verify(resultService).cancel(PAYMENT_ID);
        assertThat(counted("cancelled")).isEqualTo(1.0);
    }

    @Test
    void 승인한_적_없는_결제가_취소로_조회되면_취소로_닫는다() {
        stored(unknown());
        inquiry(Status.CANCELED);

        service().handle(request());

        verify(resultService).cancel(PAYMENT_ID);
    }

    @Test
    void 아직_승인_전이면_아무것도_하지_않는다() {
        stored(unknown());
        inquiry(Status.PENDING);

        service().handle(request());

        verifyNoInteractions(resultService);
        assertThat(counted("ignored")).isEqualTo(1.0);
    }

    @Test
    void 결제대행사_조회가_실패하면_그대로_던져_재전송을_받는다() {
        stored(unknown());
        doThrow(new PgUnknownException("read timeout", new RuntimeException()))
                .when(paymentGateway).inquireByPaymentKey(PAYMENT_KEY);

        assertThatThrownBy(() -> service().handle(request())).isInstanceOf(PgUnknownException.class);

        verifyNoInteractions(resultService);
    }

    @Test
    void 이미_확정된_결제는_승인이든_아니든_결제대행사를_부르지_않는다() {
        stored(approved());
        service().handle(request());

        Payment failed = attempted();
        failed.fail("거절");
        stored(failed);
        service().handle(request());

        Payment cancelled = attempted();
        cancelled.cancel(APPROVED_AT, null);
        stored(cancelled);
        service().handle(request());

        verifyNoInteractions(paymentGateway, resultService);
        assertThat(counted("ignored")).isEqualTo(3.0);
    }

    @Test
    void 같은_주문의_조회가_잠깐_사이에_있었으면_결제대행사를_부르지_않고_재전송을_받는다() {
        stored(unknown());
        when(inquiryGuard.tryAcquire(ORDER_ID)).thenReturn(false);

        PaymentWebhookService.Result result = service().handle(request());

        assertThat(result).isEqualTo(PaymentWebhookService.Result.THROTTLED);
        verifyNoInteractions(paymentGateway, resultService);
        assertThat(counted("throttled")).isEqualTo(1.0);
    }

    @Test
    void 처리한_웹훅은_HANDLED다() {
        stored(unknown());
        inquiry(Status.PENDING);

        assertThat(service().handle(request())).isEqualTo(PaymentWebhookService.Result.HANDLED);
    }
}
