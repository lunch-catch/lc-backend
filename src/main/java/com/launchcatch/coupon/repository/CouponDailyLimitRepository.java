package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.entity.CouponDailyLimit;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponDailyLimitRepository extends JpaRepository<CouponDailyLimit, Long> {

    // 없는 카운터만 0으로 생성하며 기존 횟수와 감사 시각은 덮어쓰지 않는다.
    @Modifying
    @Query(value = """
            INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
            VALUES (:memberId, :businessDate, 0, :now, :now)
            ON DUPLICATE KEY UPDATE member_id = member_id
            """, nativeQuery = true)
    void ensureCounter(@Param("memberId") Long memberId, @Param("businessDate") LocalDate businessDate,
            @Param("now") LocalDateTime now);

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
