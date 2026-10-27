package com.lunchcatch.billing.service;

import com.lunchcatch.billing.config.PaymentRecheckProperties;
import com.lunchcatch.billing.entity.PaymentReconciliationOutbox;
import com.lunchcatch.billing.repository.PaymentReconciliationOutboxRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 재조회할 아웃박스 행을 선점한다. 짧은 트랜잭션 하나로 끝내고 결제대행사를 부르지 않는다.
 *
 * 시각이 된 행을 SKIP LOCKED 로 잠그고 다음 재조회 시각을 선점 만료 시각으로 미룬 채 커밋한다.
 * 커밋하면 잠금이 풀리지만 시각이 미뤄졌으므로 다른 서버가 같은 행을 다시 집지 않는다.
 * 조회를 끝내기 전에 서버가 죽어도 만료 시각이 지나면 다시 대상이 된다.
 */
@Service
@RequiredArgsConstructor
public class PaymentRecheckClaimService {

    private final PaymentReconciliationOutboxRepository outboxRepository;
    private final PaymentRecheckProperties properties;
    private final Clock clock;

    // 선점한 행의 결제 ID 를 오래 기다린 순서로 돌려준다
    @Transactional
    public List<Long> claimDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime leaseUntil = now.plus(properties.claimLease());
        List<PaymentReconciliationOutbox> due = outboxRepository.findDueForClaim(now, properties.batchSize());
        due.forEach(outbox -> outbox.claim(leaseUntil));
        return due.stream().map(PaymentReconciliationOutbox::getPaymentId).toList();
    }
}
