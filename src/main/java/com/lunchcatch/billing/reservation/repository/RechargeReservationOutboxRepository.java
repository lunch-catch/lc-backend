package com.lunchcatch.billing.reservation.repository;

import com.lunchcatch.billing.reservation.entity.RechargeReservationOutbox;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 재예약 아웃박스 저장소이며 재예약 대상은 FOR UPDATE SKIP LOCKED 로 집는다
public interface RechargeReservationOutboxRepository extends JpaRepository<RechargeReservationOutbox, Long> {

    /*
     * 재예약 시각이 된 PENDING 행을 오래 기다린 순서로 잠그고 읽는다. 호출한 트랜잭션이 끝날 때까지 잠근다.
     * 다른 서버가 이미 잠근 행은 기다리지 않고 건너뛴다(SKIP LOCKED). idx_recharge_reservation_outbox_retry 를 탄다.
     * 이 테이블만 읽으므로 네이티브 쿼리가 소유 도메인 밖을 건드리지 않는다.
     */
    @Query(value = """
            select *
              from recharge_reservation_outbox
             where status = 'PENDING'
               and next_retry_at <= :now
             order by next_retry_at, recharge_reservation_outbox_id
             limit :limit
               for update skip locked
            """, nativeQuery = true)
    List<RechargeReservationOutbox> findDueForClaim(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
