package com.launchcatch.billing.service;

import com.launchcatch.billing.client.PaymentGateway;
import com.launchcatch.billing.client.PaymentGatewayInquiryResult;
import com.launchcatch.billing.dto.PaymentWebhookRequest;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PaymentRepository;
import com.launchcatch.global.exception.ConstraintViolations;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/*
 * 결제대행사의 결제 상태 웹훅을 처리한다. 승인의 주 경로가 아니라 승인 확정이 놓친 결제를 맞추는 보조 경로다.
 *
 * 본문의 상태값을 믿지 않는다. 결제 키로 결제대행사에 다시 물어 그 답으로만 결제를 바꾼다.
 * 처리할 수 없는 요청(형식 오류, 모르는 주문, 금액 불일치)은 다시 보내도 소용없으므로 로그만 남기고 조용히 끝낸다.
 * 결제대행사 조회가 실패하면 예외를 그대로 던져 5xx 로 답하게 한다. 결제대행사가 다시 보낸다.
 *
 * 트랜잭션을 걸지 않는다. 조회는 DB 연결 밖에서 하고 기록은 결과 기록 서비스의 짧은 트랜잭션에 맡긴다.
 * UNKNOWN 을 새로 만들지 않는다. 그것은 승인 확정의 몫이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    static final String WEBHOOK_METRIC = "billing.payment.webhook";

    private static final String PG_TID_UNIQUE = "uk_payment_pg_tid";
    private static final String FAILED_WITHOUT_REASON = "결제대행사 조회 결과 결제가 실패했습니다.";

    private final PaymentRepository paymentRepository;
    private final PaymentResultService resultService;
    private final PaymentGateway paymentGateway;
    private final MeterRegistry meterRegistry;

    public void handle(PaymentWebhookRequest request) {
        if (!isUsable(request)) {
            log.warn("event=PAYMENT_WEBHOOK_IGNORED reason=malformed");
            count("ignored");
            return;
        }
        String paymentKey = request.data().paymentKey();
        String orderId = request.data().orderId();
        Optional<Payment> found = paymentRepository.findByOrderId(orderId);
        if (found.isEmpty()) {
            log.warn("event=PAYMENT_WEBHOOK_IGNORED reason=unknown_order orderId={}", orderId);
            count("ignored");
            return;
        }
        Payment payment = found.get();
        if (payment.getStatus() == PaymentStatus.FAILED || payment.getStatus() == PaymentStatus.CANCELLED) {
            count("ignored");
            return;
        }
        PaymentGatewayInquiryResult inquiry = paymentGateway.inquireByPaymentKey(paymentKey);
        if (!matches(payment, inquiry)) {
            log.warn("event=PAYMENT_WEBHOOK_IGNORED reason=mismatch paymentId={} status={}",
                    payment.getId(), inquiry.status());
            count("ignored");
            return;
        }
        apply(payment, paymentKey, inquiry);
    }

    private void apply(Payment payment, String paymentKey, PaymentGatewayInquiryResult inquiry) {
        boolean approved = payment.getStatus() == PaymentStatus.APPROVED;
        switch (inquiry.status()) {
            case APPROVED -> {
                if (approved) {
                    count("ignored");
                } else {
                    approve(payment, paymentKey, inquiry);
                }
            }
            case FAILED -> {
                if (approved || payment.getPgTid() == null) {
                    // 승인 시도 전에 결제창이 닫힌 주문은 결제 식별자가 없어 실패로 닫을 수 없다
                    count("ignored");
                } else {
                    resultService.fail(payment.getId(), failureReason(inquiry));
                    count("failed");
                }
            }
            case EXPIRED -> {
                if (approved) {
                    count("ignored");
                } else {
                    resultService.cancel(payment.getId());
                    count("cancelled");
                }
            }
            case CANCELED -> cancelled(payment, approved);
            case PENDING, NOT_FOUND -> count("ignored");
        }
    }

    /*
     * 승인 뒤 결제대행사에서 취소됐으면 자동으로 충전을 되돌리지 않는다. 로그를 남기고 대사가 불일치로 기록한다.
     * 우리가 승인한 적 없는 결제가 취소됐으면 충전 없이 취소로 닫는다.
     */
    private void cancelled(Payment payment, boolean approved) {
        if (approved) {
            log.error("event=PAYMENT_CANCELED_AFTER_APPROVAL paymentId={}", payment.getId());
            count("ignored");
            return;
        }
        resultService.cancel(payment.getId());
        count("cancelled");
    }

    /*
     * 승인 시도 전 주문이면 결제 식별자를 먼저 적고 승인한다. 결제 식별자 없이는 APPROVED 로 둘 수 없다.
     * 다른 결제가 이미 그 식별자를 쓰고 있거나 이 주문에 다른 식별자가 적혀 있으면 이 요청은 처리할 수 없다.
     */
    private void approve(Payment payment, String paymentKey, PaymentGatewayInquiryResult inquiry) {
        if (payment.getStatus() == PaymentStatus.REQUESTED && !assignPgTid(payment, paymentKey)) {
            count("ignored");
            return;
        }
        resultService.approve(payment.getId(), inquiry.approvedAt());
        count("approved");
    }

    private boolean assignPgTid(Payment payment, String paymentKey) {
        try {
            resultService.assignPgTid(payment.getId(), paymentKey);
            return true;
        } catch (BillingException e) {
            if (e.getErrorCode() != BillingErrorCode.ORDER_ID_MISMATCH) {
                throw e;
            }
            log.warn("event=PAYMENT_WEBHOOK_IGNORED reason=pg_tid_conflict paymentId={}", payment.getId());
            return false;
        } catch (DataIntegrityViolationException e) {
            if (!ConstraintViolations.isConstraintViolation(e, PG_TID_UNIQUE)) {
                throw e;
            }
            log.warn("event=PAYMENT_WEBHOOK_IGNORED reason=pg_tid_already_used paymentId={}", payment.getId());
            return false;
        }
    }

    private boolean isUsable(PaymentWebhookRequest request) {
        return request != null
                && PaymentWebhookRequest.STATUS_CHANGED.equals(request.eventType())
                && request.data() != null
                && request.data().paymentKey() != null && !request.data().paymentKey().isBlank()
                && request.data().orderId() != null && !request.data().orderId().isBlank();
    }

    private boolean matches(Payment payment, PaymentGatewayInquiryResult inquiry) {
        if (inquiry.status() == PaymentGatewayInquiryResult.Status.NOT_FOUND) {
            return false;
        }
        return payment.getOrderId().equals(inquiry.orderId()) && payment.getAmount() == inquiry.amount();
    }

    private String failureReason(PaymentGatewayInquiryResult inquiry) {
        String message = inquiry.failureMessage();
        String reason = message == null || message.isBlank() ? FAILED_WITHOUT_REASON : message;
        return inquiry.failureCode() == null ? reason : inquiry.failureCode() + ": " + reason;
    }

    private void count(String result) {
        meterRegistry.counter(WEBHOOK_METRIC, "result", result).increment();
    }
}
