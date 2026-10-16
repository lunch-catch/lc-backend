package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.entity.CouponUsageHistory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponUsageHistoryRepository
        extends JpaRepository<CouponUsageHistory, Long>, CouponUsageHistoryRepositoryCustom {

    Optional<CouponUsageHistory> findByMemberCouponId(Long memberCouponId);

    List<CouponUsageHistory> findAllByMemberCouponIdIn(List<Long> memberCouponIds);
}
