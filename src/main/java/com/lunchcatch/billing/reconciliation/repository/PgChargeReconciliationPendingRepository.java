package com.lunchcatch.billing.reconciliation.repository;

import com.lunchcatch.billing.reconciliation.entity.PgChargeReconciliationPending;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PgChargeReconciliationPendingRepository
        extends JpaRepository<PgChargeReconciliationPending, Long> {

    boolean existsByPaymentId(Long paymentId);

    List<PgChargeReconciliationPending> findAllByOrderByCreatedAtAsc();

    void deleteByPaymentId(Long paymentId);
}
