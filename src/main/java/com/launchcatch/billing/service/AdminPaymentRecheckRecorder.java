package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.PaymentRecheckVerdict;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.ops.contract.AuditLogWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자 재조회의 판정을 기록한다. 결제 결과와 감사 로그를 한 트랜잭션으로 묶는다.
 * 결제만 바뀌고 누가 바꿨는지 남지 않는 일도, 기록 없이 감사 로그만 남는 일도 없게 하려는 것이다.
 * 결제대행사 조회는 이 트랜잭션 밖에서 끝나 있다.
 */
@Service
@RequiredArgsConstructor
public class AdminPaymentRecheckRecorder {

    static final String AUDIT_ACTION = "PAYMENT_RECHECK";

    private final PaymentResultService resultService;
    private final AuditLogWriter auditLogWriter;

    @Transactional
    public Payment record(Long adminId, Long paymentId, PaymentRecheckVerdict verdict) {
        Payment payment = switch (verdict.decision()) {
            case APPROVE -> resultService.approve(paymentId, verdict.approvedAt());
            case FAIL -> resultService.fail(paymentId, verdict.reason());
            case CANCEL -> resultService.cancel(paymentId);
            case MISS -> resultService.recordAdminRecheckMiss(paymentId, verdict.reason());
        };
        auditLogWriter.write(adminId, AUDIT_ACTION, String.valueOf(paymentId),
                "decision=" + verdict.decision() + ";status=" + payment.getStatus());
        return payment;
    }
}
