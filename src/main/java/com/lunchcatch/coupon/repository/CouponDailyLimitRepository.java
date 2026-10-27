package com.lunchcatch.coupon.repository;

import com.lunchcatch.coupon.entity.CouponDailyLimit;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    // 같은 회원의 쿠폰을 동시에 삭제해도 횟수 차감이 덮어써지지 않도록 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CouponDailyLimit> findByMemberIdAndBusinessDate(Long memberId, LocalDate businessDate);
}
