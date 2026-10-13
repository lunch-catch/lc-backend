package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 결제 저장소이며 점주별 상태 확인은 idx_payment_owner_status 를 탄다
public interface PaymentRepository extends JpaRepository<Payment, Long>, PaymentRepositoryCustom {

    // 그 점주에게 이 상태의 결제가 하나라도 있는지 본다
    boolean existsByOwnerIdAndStatus(Long ownerId, PaymentStatus status);

    /*
     * 결제 행을 잠그고 읽는다. 결과 기록은 모두 이 잠금 아래에서 상태를 다시 보고 쓴다.
     * 승인 확정, 웹훅, 재조회 워커가 같은 결제를 동시에 확정하려 해도 뒤에 온 쪽이 앞의 결과를 보고 멈춘다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);

    // 주문 번호는 결제당 하나다(uk_payment_order_id). 웹훅이 이 값으로 우리 결제를 찾는다
    Optional<Payment> findByOrderId(String orderId);

    // 그 점주의 결제 한 건이다. 남의 결제는 없는 것과 같다
    Optional<Payment> findByIdAndOwnerId(Long id, Long ownerId);

    /*
     * 점주 결제 내역의 첫 페이지다. 요청 시각 내림차순이고 같은 시각은 ID 내림차순이다(idx_payment_owner_created).
     * 승인 시도 전에 닫힌 주문(pg_tid 가 NULL)은 뺀다. 결제창을 열었다가 이탈한 주문이 이력에 쌓이지 않게 한다.
     */
    @Query("""
            select p from Payment p
             where p.ownerId = :ownerId
               and p.pgTid is not null
             order by p.createdAt desc, p.id desc
            """)
    List<Payment> findOwnerHistory(@Param("ownerId") Long ownerId, Pageable limit);

    // 커서 다음 페이지이며 조건과 정렬은 첫 페이지와 같다
    @Query("""
            select p from Payment p
             where p.ownerId = :ownerId
               and p.pgTid is not null
               and (p.createdAt < :cursorCreatedAt
                    or (p.createdAt = :cursorCreatedAt and p.id < :cursorId))
             order by p.createdAt desc, p.id desc
            """)
    List<Payment> findOwnerHistoryAfter(@Param("ownerId") Long ownerId,
                                        @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                        @Param("cursorId") Long cursorId,
                                        Pageable limit);
}
