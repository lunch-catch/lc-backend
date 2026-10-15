package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {

    // 요청한 매장의 오늘 조회 대상 이벤트를 한 번에 읽으며 상태나 재고를 변경하지 않는다.
    List<CouponEvent> findByStoreIdInAndBusinessDateAndStatusIn(
            List<Long> storeIds, LocalDate businessDate, List<CouponEventStatus> statuses);

    // 이벤트가 소유한 재고를 상태별로 집계하며 재고 행을 잠그지 않는다.
    @Query("""
            SELECT inventory.couponEventId AS eventId, COUNT(inventory) AS quantity
              FROM CouponInventory inventory
             WHERE inventory.couponEventId IN :eventIds AND inventory.status = :status
             GROUP BY inventory.couponEventId
            """)
    List<InventoryQuantity> findQuantitiesByEventIdsAndStatus(
            @Param("eventIds") List<Long> eventIds, @Param("status") CouponInventoryStatus status);

    interface InventoryQuantity {
        Long getEventId();
        long getQuantity();
    }
}
