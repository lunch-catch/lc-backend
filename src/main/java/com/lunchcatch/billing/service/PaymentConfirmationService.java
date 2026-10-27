package com.lunchcatch.billing.service;

import static com.lunchcatch.billing.PaymentConstants.FAILED_WITHOUT_REASON;
import static com.lunchcatch.billing.PaymentConstants.PG_TID_UNIQUE;

import com.lunchcatch.billing.client.PaymentGateway;
import com.lunchcatch.billing.client.PaymentGatewayApproval;
import com.lunchcatch.billing.client.PaymentGatewayInquiryResult;
import com.lunchcatch.billing.client.PgNotSentException;
import com.lunchcatch.billing.client.PgRejectedException;
import com.lunchcatch.billing.client.PgUnknownException;
import com.lunchcatch.billing.dto.PaymentConfirmResponse;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PaymentStatus;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PaymentRepository;
import com.lunchcatch.global.exception.ConstraintViolations;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/*
 * 점주의 승인 확정 요청을 처리한다.
 *
 * 트랜잭션을 메서드에 걸지 않는다. 결제대행사 호출을 트랜잭션 밖에 두려고 경계를 셋으로 나눈다.
 * 먼저 트랜잭션 없이 결제를 읽고 소유자, 주문 번호, 상태를 본다. 다음으로 결제 식별자만 짧은 트랜잭션으로
 * 커밋한다. 마지막으로 결제대행사를 부르고 그 결과를 짧은 트랜잭션으로 기록한다. 기록은 결과 기록
 * 서비스가 결제 행을 잠그고 한다.
 *
 * 결제대행사 응답이 불명이면 저장한 결제 키로 한 번만 다시 조회한다. 그래도 정하지 못하면 UNKNOWN 으로 두고
 * 재조회 워커에 넘긴다. 토스가 이미 처리된 결제라고 답하는 경우(ALREADY_PROCESSED_PAYMENT)도 게이트웨이가
 * 불명으로 분류하므로 이 조회가 결과를 정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmationService {

    static final String CONFIRM_METRIC = "billing.payment.confirm";

    private static final String REJECTED_WITHOUT_REASON = "결제대행사가 승인을 거절했습니다.";

    private final PaymentRepository paymentRepository;
    private final PaymentResultService resultService;
    private final PaymentGateway paymentGateway;
    private final MeterRegistry meterRegistry;

    // 첫 사건 전에도 시리즈가 있어야 increase() 가 첫 증가를 센다
    @PostConstruct
    void registerCounters() {
        for (String result : List.of("approved", "unknown", "failed", "not_sent")) {
            meterRegistry.counter(CONFIRM_METRIC, "result", result);
        }
    }

    /*
     * 본인 결제가 아니거나 주문 번호가 다르면 결제대행사를 부르지 않는다.
     * 이미 결과가 확정됐거나 확인 중인 결제도 다시 부르지 않고 지금 상태로 답한다.
     */
    public PaymentConfirmResponse confirm(Long ownerId, Long paymentId, String orderId, String pgTid) {
        Payment payment = paymentRepository.findById(paymentId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new BillingException(BillingErrorCode.PAYMENT_NOT_FOUND));
        if (!payment.getOrderId().equals(orderId)) {
            throw new BillingException(BillingErrorCode.ORDER_ID_MISMATCH);
        }
        if (payment.getStatus() != PaymentStatus.REQUESTED) {
            return respondWithoutCall(payment);
        }
        Payment attempted = assignPgTid(paymentId, pgTid);
        if (attempted.getStatus() != PaymentStatus.REQUESTED) {
            return respondWithoutCall(attempted);
        }
        Payment result = callGateway(attempted, pgTid);
        meterRegistry.counter(CONFIRM_METRIC, "result", metricResult(result.getStatus())).increment();
        return respondAfterCall(result);
    }

    /*
     * 결제 식별자를 먼저 커밋한다.
     * 다른 결제가 이미 같은 식별자를 쓰고 있으면 이 주문의 결제창에서 받은 값이 아니므로 주문 정보 불일치로 거절한다.
     */
    private Payment assignPgTid(Long paymentId, String pgTid) {
        try {
            return resultService.assignPgTid(paymentId, pgTid);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isConstraintViolation(e, PG_TID_UNIQUE)) {
                log.warn("event=PAYMENT_PG_TID_ALREADY_USED paymentId={}", paymentId);
                throw new BillingException(BillingErrorCode.ORDER_ID_MISMATCH, e);
            }
            throw e;
        }
    }

    /*
     * 승인을 요청하고 결과를 기록한다. 금액은 주문 때 저장한 값이다.
     * 승인 응답의 주문 번호나 금액이 저장값과 다르면 승인으로 보지 않는다. 실패로 단정하지도 않고 재조회와 관리자 확인에 맡긴다.
     */
    private Payment callGateway(Payment payment, String pgTid) {
        PaymentGatewayApproval approval;
        try {
            approval = paymentGateway.confirm(pgTid, payment.getOrderId(), payment.getAmount());
        } catch (PgRejectedException e) {
            return resultService.fail(payment.getId(), rejectionReason(e));
        } catch (PgNotSentException e) {
            meterRegistry.counter(CONFIRM_METRIC, "result", "not_sent").increment();
            throw new BillingException(BillingErrorCode.PG_REQUEST_NOT_SENT, e);
        } catch (PgUnknownException e) {
            return recheck(payment);
        }
        if (!matches(payment, approval.orderId(), approval.amount())) {
            log.error("event=PAYMENT_APPROVAL_MISMATCH paymentId={} approvedAmount={} storedAmount={}",
                    payment.getId(), approval.amount(), payment.getAmount());
            return resultService.markUnknown(payment.getId());
        }
        return resultService.approve(payment.getId(), approval.approvedAt());
    }

    /*
     * 응답이 불명일 때 저장한 결제 키로 한 번만 다시 조회한다. 그 이상은 재조회 워커의 몫이다.
     * 종료 결과는 주문 번호와 금액이 저장값과 일치할 때만 기록한다. 승인 뒤 결제대행사에서 취소된 결제(CANCELED)도
     * 우리 쪽은 승인한 적이 없으므로 충전 없이 취소로 닫는다.
     */
    private Payment recheck(Payment payment) {
        PaymentGatewayInquiryResult inquiry;
        try {
            inquiry = paymentGateway.inquireByPaymentKey(payment.getPgTid());
        } catch (PgNotSentException | PgUnknownException e) {
            log.warn("event=PAYMENT_CONFIRM_RECHECK_FAILED paymentId={}", payment.getId(), e);
            return resultService.markUnknown(payment.getId());
        }
        if (inquiry.status() != PaymentGatewayInquiryResult.Status.PENDING
                && inquiry.status() != PaymentGatewayInquiryResult.Status.NOT_FOUND
                && !matches(payment, inquiry.orderId(), inquiry.amount())) {
            log.error("event=PAYMENT_INQUIRY_MISMATCH paymentId={} inquiredAmount={} storedAmount={}",
                    payment.getId(), inquiry.amount(), payment.getAmount());
            return resultService.markUnknown(payment.getId());
        }
        return switch (inquiry.status()) {
            case APPROVED -> resultService.approve(payment.getId(), inquiry.approvedAt());
            case FAILED -> resultService.fail(payment.getId(), inquiryFailureReason(inquiry));
            case EXPIRED, CANCELED -> resultService.cancel(payment.getId());
            case PENDING, NOT_FOUND -> resultService.markUnknown(payment.getId());
        };
    }

    private boolean matches(Payment payment, String orderId, long amount) {
        return payment.getOrderId().equals(orderId) && payment.getAmount() == amount;
    }

    /*
     * 호출 전에 이미 상태가 정해진 결제에 답한다.
     * 취소된 주문은 새로 주문해야 한다는 뜻의 BILLING-009 다.
     */
    private PaymentConfirmResponse respondWithoutCall(Payment payment) {
        return switch (payment.getStatus()) {
            case APPROVED, UNKNOWN -> PaymentConfirmResponse.from(payment);
            case FAILED -> throw new BillingException(BillingErrorCode.PAYMENT_REJECTED);
            case CANCELLED -> throw new BillingException(BillingErrorCode.PAYMENT_ALREADY_CANCELLED);
            case REQUESTED -> throw new IllegalStateException("승인 시도 전 결제는 결제대행사를 불러야 합니다.");
        };
    }

    /*
     * 이번 호출로 정해진 결과에 답한다.
     * 실패와 승인 전 만료는 모두 충전 없이 끝났다는 뜻의 BILLING-003 이다.
     */
    private PaymentConfirmResponse respondAfterCall(Payment payment) {
        return switch (payment.getStatus()) {
            case APPROVED, UNKNOWN -> PaymentConfirmResponse.from(payment);
            case FAILED, CANCELLED -> throw new BillingException(BillingErrorCode.PAYMENT_REJECTED);
            case REQUESTED -> throw new IllegalStateException("결제대행사 호출 뒤에 결과가 기록되지 않았습니다.");
        };
    }

    private String metricResult(PaymentStatus status) {
        return switch (status) {
            case APPROVED -> "approved";
            case UNKNOWN -> "unknown";
            case FAILED, CANCELLED, REQUESTED -> "failed";
        };
    }

    private String rejectionReason(PgRejectedException e) {
        String message = e.getMessage();
        String reason = message == null || message.isBlank() ? REJECTED_WITHOUT_REASON : message;
        return e.getPgCode() == null ? reason : e.getPgCode() + ": " + reason;
    }

    private String inquiryFailureReason(PaymentGatewayInquiryResult inquiry) {
        String message = inquiry.failureMessage();
        String reason = message == null || message.isBlank() ? FAILED_WITHOUT_REASON : message;
        return inquiry.failureCode() == null ? reason : inquiry.failureCode() + ": " + reason;
    }
}
