package com.launchcatch.billing.service;

import static com.launchcatch.billing.PaymentConstants.FAILED_WITHOUT_REASON;

import com.launchcatch.billing.client.PaymentGateway;
import com.launchcatch.billing.client.PaymentGatewayInquiryResult;
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
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 확인 중(UNKNOWN)인 결제를 결제대행사에 다시 물어 확정한다. 재조회 워커가 쓴다.
 *
 * 트랜잭션을 걸지 않는다. 결제대행사 조회를 DB 연결 밖에서 하고, 결과 기록은 결과 기록 서비스의
 * 짧은 트랜잭션에 맡긴다.
 *
 * 돈이 걸린 일이라 조회가 분명한 답을 줄 때만 자동으로 반영한다. 처리 중, 결제 없음, 조회 실패,
 * 주문 번호나 금액 불일치는 어느 것도 닫지 않고 시도만 기록해 다시 묻는다. 상한을 넘기면 사람이 확정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRecheckService {

    static final String RECHECK_METRIC = "billing.payment.recheck";

    private static final String PENDING_REASON = "결제대행사가 아직 처리 중입니다.";
    private static final String NOT_FOUND_REASON = "결제대행사에 결제가 없습니다.";
    private static final String INQUIRY_FAILED_REASON = "결제대행사 조회에 실패했습니다.";
    private static final String MISMATCH_REASON = "조회 결과의 주문 번호나 금액이 저장값과 다릅니다.";

    private final PaymentRepository paymentRepository;
    private final PaymentRecheckClaimService claimService;
    private final PaymentResultService resultService;
    private final PaymentGateway paymentGateway;
    private final MeterRegistry meterRegistry;

    // 첫 사건 전에도 시리즈가 있어야 increase() 가 첫 증가를 센다
    @PostConstruct
    void registerCounters() {
        for (Decision decision : Decision.values()) {
            meterRegistry.counter(RECHECK_METRIC, "result", decision.name().toLowerCase(Locale.ROOT));
        }
        meterRegistry.counter(RECHECK_METRIC, "result", "error");
    }

    /*
     * 재조회할 행을 선점하고 하나씩 처리한다. 한 건이 실패해도 다음 건을 계속한다.
     * 처리 중 예외가 난 건은 선점 만료 시각이 지나면 다시 대상이 된다.
     * 처리를 시도한 건수를 돌려준다.
     */
    public int recheckDue() {
        List<Long> paymentIds = claimService.claimDue();
        for (Long paymentId : paymentIds) {
            try {
                recheck(paymentId);
            } catch (RuntimeException e) {
                log.warn("event=BILLING_PAYMENT_RECHECK_FAILED paymentId={} exceptionType={}",
                        paymentId, e.getClass().getSimpleName());
                meterRegistry.counter(RECHECK_METRIC, "result", "error").increment();
            }
        }
        return paymentIds.size();
    }

    /*
     * 결제 한 건을 다시 조회해 반영한다. 이미 확정된 결제는 부르지 않고 지금 상태를 돌려준다.
     * 그 사이 웹훅이나 관리자가 확정했을 수 있다.
     */
    public Payment recheck(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getStatus() != PaymentStatus.UNKNOWN) {
            return payment;
        }
        PaymentRecheckVerdict verdict = judge(payment);
        Payment result = switch (verdict.decision()) {
            case APPROVE -> resultService.approve(paymentId, verdict.approvedAt());
            case FAIL -> resultService.fail(paymentId, verdict.reason());
            case CANCEL -> resultService.cancel(paymentId);
            case MISS -> resultService.recordRecheckMiss(paymentId, verdict.reason());
        };
        meterRegistry.counter(RECHECK_METRIC, "result", verdict.decision().name().toLowerCase(Locale.ROOT))
                .increment();
        return result;
    }

    /*
     * 저장한 결제 키로 결제대행사에 묻고 판정한다. 결제를 바꾸지 않는다. 관리자 재조회도 같은 판정을 쓴다.
     * 종료 결과는 주문 번호와 금액이 저장값과 일치할 때만 판정한다. 승인 뒤 결제대행사에서 취소된 결제(CANCELED)도
     * 우리 쪽은 승인한 적이 없으므로 충전 없이 취소로 닫는다.
     */
    public PaymentRecheckVerdict judge(Payment payment) {
        PaymentGatewayInquiryResult inquiry;
        try {
            inquiry = paymentGateway.inquireByPaymentKey(payment.getPgTid());
        } catch (PgNotSentException | PgUnknownException e) {
            log.warn("event=PAYMENT_RECHECK_INQUIRY_FAILED paymentId={}", payment.getId(), e);
            return PaymentRecheckVerdict.miss(INQUIRY_FAILED_REASON);
        }
        if (inquiry.status() != PaymentGatewayInquiryResult.Status.PENDING
                && inquiry.status() != PaymentGatewayInquiryResult.Status.NOT_FOUND
                && !matches(payment, inquiry.orderId(), inquiry.amount())) {
            log.error("event=PAYMENT_INQUIRY_MISMATCH paymentId={} inquiredAmount={} storedAmount={}",
                    payment.getId(), inquiry.amount(), payment.getAmount());
            return PaymentRecheckVerdict.miss(MISMATCH_REASON);
        }
        return switch (inquiry.status()) {
            case APPROVED -> PaymentRecheckVerdict.approve(inquiry.approvedAt());
            case FAILED -> PaymentRecheckVerdict.fail(failureReason(inquiry));
            case EXPIRED, CANCELED -> PaymentRecheckVerdict.cancel();
            case PENDING -> PaymentRecheckVerdict.miss(PENDING_REASON);
            case NOT_FOUND -> PaymentRecheckVerdict.miss(NOT_FOUND_REASON);
        };
    }

    private boolean matches(Payment payment, String orderId, long amount) {
        return payment.getOrderId().equals(orderId) && payment.getAmount() == amount;
    }

    private String failureReason(PaymentGatewayInquiryResult inquiry) {
        String message = inquiry.failureMessage();
        String reason = message == null || message.isBlank() ? FAILED_WITHOUT_REASON : message;
        return inquiry.failureCode() == null ? reason : inquiry.failureCode() + ": " + reason;
    }
}
