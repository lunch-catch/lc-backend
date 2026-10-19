package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.entity.CouponInventory;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface CouponInventoryRepository extends JpaRepository<CouponInventory, Long> {

    boolean existsByCouponEventIdAndStatus(Long couponEventId, CouponInventoryStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CouponInventory> findFirstByCouponEventIdAndStatusOrderBySequenceNoAsc(
            Long couponEventId, CouponInventoryStatus status);
}
