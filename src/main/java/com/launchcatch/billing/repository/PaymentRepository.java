package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 결제 저장소이며 점주별 상태 확인은 idx_payment_owner_status 를 탄다
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // 그 점주에게 이 상태의 결제가 하나라도 있는지 본다
    boolean existsByOwnerIdAndStatus(Long ownerId, PaymentStatus status);

    /*
     * 결제 행을 잠그고 읽는다. 결과 기록은 모두 이 잠금 아래에서 상태를 다시 보고 쓴다.
     * 승인 확정, 웹훅, 재조회 워커가 같은 결제를 동시에 확정하려 해도 뒤에 온 쪽이 앞의 결과를 보고 멈춘다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);
}
