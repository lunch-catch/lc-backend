package com.lunchcatch.coupon.repository;

import com.lunchcatch.coupon.entity.CouponInventory;
import com.lunchcatch.coupon.entity.CouponInventoryStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponInventoryRepository extends JpaRepository<CouponInventory, Long> {

    // 이벤트에 해당 상태의 재고가 한 장이라도 있으면 true를 돌려주며 소진 확인에 쓴다.
    boolean existsByCouponEventIdAndStatus(Long couponEventId, CouponInventoryStatus status);

    // 이벤트의 순번 하나에 해당하는 재고 행을 읽는다.
    Optional<CouponInventory> findByCouponEventIdAndSequenceNo(Long couponEventId, int sequenceNo);

    // 이벤트의 AVAILABLE 재고 순번을 오름차순으로 읽으며 큐 복구가 available 목록을 채울 때 쓴다.
    @Query("""
            SELECT inventory.sequenceNo FROM CouponInventory inventory
             WHERE inventory.couponEventId = :eventId
               AND inventory.status = com.lunchcatch.coupon.entity.CouponInventoryStatus.AVAILABLE
             ORDER BY inventory.sequenceNo
            """)
    List<Integer> findAvailableSequences(@Param("eventId") Long eventId);

    // 이벤트에서 해당 상태인 재고 수를 세며 큐 복구가 발급된 수를 구할 때 쓴다.
    long countByCouponEventIdAndStatus(Long eventId, CouponInventoryStatus status);
}
