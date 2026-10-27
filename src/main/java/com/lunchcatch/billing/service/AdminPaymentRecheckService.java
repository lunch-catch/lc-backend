package com.lunchcatch.billing.service;

import com.lunchcatch.billing.dto.AdminPaymentItem;
import com.lunchcatch.billing.dto.PaymentRecheckVerdict;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PaymentStatus;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 확인 중(UNKNOWN)인 결제를 관리자가 직접 다시 조회해 확정한다.
 * 정상 경로는 재조회 워커가 자동으로 하고, 이것은 상한을 넘겨 굳은 건을 사람이 확정하는 용도다.
 *
 * 트랜잭션을 걸지 않는다. 결제대행사 조회를 먼저 끝내고, 결과 기록과 감사 로그만 짧은 트랜잭션 하나에 담는다.
 * 워커와 같은 판정을 쓰되 확정하지 못했을 때는 시도 횟수를 건드리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminPaymentRecheckService {

    private final PaymentRepository paymentRepository;
    private final PaymentRecheckService recheckService;
    private final AdminPaymentRecheckRecorder recorder;
    private final AdminPaymentQueryService queryService;

    public AdminPaymentItem recheck(Long adminId, Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getStatus() != PaymentStatus.UNKNOWN) {
            throw new BillingException(BillingErrorCode.PAYMENT_NOT_RECHECKABLE);
        }
        PaymentRecheckVerdict verdict = recheckService.judge(payment);
        recorder.record(adminId, paymentId, verdict);
        return queryService.get(paymentId);
    }
}
