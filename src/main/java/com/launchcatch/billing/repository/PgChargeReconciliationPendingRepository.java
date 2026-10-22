package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.PgChargeReconciliationPending;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PgChargeReconciliationPendingRepository
        extends JpaRepository<PgChargeReconciliationPending, Long> {

    boolean existsByPaymentId(Long paymentId);

    List<PgChargeReconciliationPending> findAllByOrderByCreatedAtAsc();

    void deleteByPaymentId(Long paymentId);
}
