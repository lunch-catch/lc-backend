package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.entity.CouponInventory;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponInventoryRepository extends JpaRepository<CouponInventory, Long> {

    boolean existsByCouponEventIdAndStatus(Long couponEventId, CouponInventoryStatus status);

    // 발급된 앞 순번을 반복해서 훑지 않도록 이벤트·상태·순번 인덱스로 재고 한 장을 잠근다.
    @Query(value = """
            SELECT inventory.*
              FROM coupon_inventory inventory
              FORCE INDEX (idx_coupon_inventory_event_status_sequence)
             WHERE inventory.coupon_event_id = :eventId
               AND inventory.status = :#{#status.name()}
             ORDER BY inventory.sequence_no
             LIMIT 1 FOR UPDATE
            """, nativeQuery = true)
    Optional<CouponInventory> findFirstByCouponEventIdAndStatusOrderBySequenceNoAsc(
            @Param("eventId") Long couponEventId, @Param("status") CouponInventoryStatus status);
}
