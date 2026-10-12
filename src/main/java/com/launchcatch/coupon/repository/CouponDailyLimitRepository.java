package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.entity.CouponDailyLimit;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponDailyLimitRepository extends JpaRepository<CouponDailyLimit, Long> {

    @Query("""
            SELECT dailyLimit.issuedCount
              FROM CouponDailyLimit dailyLimit
             WHERE dailyLimit.memberId = :memberId
               AND dailyLimit.businessDate = :businessDate
            """)
    Optional<Integer> findIssuedCountByMemberIdAndBusinessDate(
            @Param("memberId") Long memberId, @Param("businessDate") LocalDate businessDate);
}
