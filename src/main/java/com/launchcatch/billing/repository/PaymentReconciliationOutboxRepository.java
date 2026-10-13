package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.PaymentReconciliationOutbox;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 결제 재조회 아웃박스 저장소이며 재조회 대상 조회(SKIP LOCKED)는 재조회 워커 작업이 더한다
public interface PaymentReconciliationOutboxRepository extends JpaRepository<PaymentReconciliationOutbox, Long> {

    // 결제당 한 행이다(uk_payment_reconciliation_outbox_payment)
    Optional<PaymentReconciliationOutbox> findByPaymentId(Long paymentId);
}
