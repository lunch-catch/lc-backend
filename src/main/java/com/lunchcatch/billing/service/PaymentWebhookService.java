package com.lunchcatch.billing.service;

import static com.lunchcatch.billing.PaymentConstants.FAILED_WITHOUT_REASON;
import static com.lunchcatch.billing.PaymentConstants.PG_TID_UNIQUE;

import com.lunchcatch.billing.cache.WebhookInquiryGuard;
import com.lunchcatch.billing.client.PaymentGateway;
import com.lunchcatch.billing.client.PaymentGatewayInquiryResult;
import com.lunchcatch.billing.dto.PaymentWebhookRequest;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PaymentStatus;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PaymentRepository;
import com.lunchcatch.global.exception.ConstraintViolations;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
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
 * 같은 주문의 조회는 잠깐 사이에 한 번만 한다. 막힌 요청은 THROTTLED 로 알려 결제대행사가 나중에 다시 보내게 한다.
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

    private final PaymentRepository paymentRepository;
    private final PaymentResultService resultService;
    private final PaymentGateway paymentGateway;
    private final WebhookInquiryGuard inquiryGuard;
    private final MeterRegistry meterRegistry;

    // 첫 사건 전에도 시리즈가 있어야 increase() 가 첫 증가를 센다
    @PostConstruct
    void registerCounters() {
        for (String result : List.of("approved", "cancelled", "ignored", "throttled", "failed")) {
            meterRegistry.counter(WEBHOOK_METRIC, "result", result);
        }
    }

    // 결제대행사 조회를 막은 요청은 THROTTLED 로 알려 재전송을 받게 한다. 나머지는 처리했거나 무시한 것이다
    public enum Result {
        HANDLED, THROTTLED
    }

    public Result handle(PaymentWebhookRequest request) {
        if (!isUsable(request)) {
            log.warn("event=BILLING_PAYMENT_WEBHOOK_SKIPPED reason=MALFORMED");
            count("ignored");
            return Result.HANDLED;
        }
        String paymentKey = request.data().paymentKey();
        String orderId = request.data().orderId();
        Optional<Payment> found = paymentRepository.findByOrderId(orderId);
        if (found.isEmpty()) {
            log.warn("event=BILLING_PAYMENT_WEBHOOK_SKIPPED reason=UNKNOWN_ORDER orderId={}", orderId);
            count("ignored");
            return Result.HANDLED;
        }
        Payment payment = found.get();
        if (isDetermined(payment)) {
            count("ignored");
            return Result.HANDLED;
        }
        if (!inquiryGuard.tryAcquire(orderId)) {
            count("throttled");
            return Result.THROTTLED;
        }
        PaymentGatewayInquiryResult inquiry = paymentGateway.inquireByPaymentKey(paymentKey);
        if (!matches(payment, inquiry)) {
            log.warn("event=BILLING_PAYMENT_WEBHOOK_SKIPPED reason=MISMATCH paymentId={} status={}",
                    payment.getId(), inquiry.status());
            count("ignored");
            return Result.HANDLED;
        }
        apply(payment, paymentKey, inquiry);
        return Result.HANDLED;
    }

    private void apply(Payment payment, String paymentKey, PaymentGatewayInquiryResult inquiry) {
        switch (inquiry.status()) {
            case APPROVED -> approve(payment, paymentKey, inquiry);
            case FAILED -> {
                if (payment.getPgTid() == null) {
                    // 승인 시도 전에 결제창이 닫힌 주문은 결제 식별자가 없어 실패로 닫을 수 없다
                    count("ignored");
                } else {
                    resultService.fail(payment.getId(), failureReason(inquiry));
                    count("failed");
                }
            }
            case EXPIRED, CANCELED -> {
                resultService.cancel(payment.getId());
                count("cancelled");
            }
            case PENDING, NOT_FOUND -> count("ignored");
        }
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
            log.warn("event=BILLING_PAYMENT_WEBHOOK_SKIPPED reason=PG_TID_CONFLICT paymentId={}", payment.getId());
            return false;
        } catch (DataIntegrityViolationException e) {
            if (!ConstraintViolations.isConstraintViolation(e, PG_TID_UNIQUE)) {
                throw e;
            }
            log.warn("event=BILLING_PAYMENT_WEBHOOK_SKIPPED reason=PG_TID_ALREADY_USED paymentId={}", payment.getId());
            return false;
        }
    }

    // 이미 결과가 정해진 결제는 결제대행사에 묻지 않는다. 승인 뒤 취소는 대사가 불일치로 기록한다
    private boolean isDetermined(Payment payment) {
        PaymentStatus status = payment.getStatus();
        return status == PaymentStatus.APPROVED || status == PaymentStatus.FAILED
                || status == PaymentStatus.CANCELLED;
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
