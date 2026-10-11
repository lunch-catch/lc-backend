package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

// 결제 저장소이며 점주별 상태 확인은 idx_payment_owner_status 를 탄다
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // 그 점주에게 이 상태의 결제가 하나라도 있는지 본다
    boolean existsByOwnerIdAndStatus(Long ownerId, PaymentStatus status);
}
