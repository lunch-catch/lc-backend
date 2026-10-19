package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.contract.DailyMemberCouponStats;
import com.launchcatch.coupon.entity.CouponUsageHistory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponUsageHistoryRepository
        extends JpaRepository<CouponUsageHistory, Long>, CouponUsageHistoryRepositoryCustom {

    Optional<CouponUsageHistory> findByMemberCouponId(Long memberCouponId);

    List<CouponUsageHistory> findAllByMemberCouponIdIn(List<Long> memberCouponIds);

    // 저장된 한국 사용 시각을 범위로 좁힌 뒤 날짜별로 묶으며 모든 캠페인을 포함한다.
    @Query(value = """
            SELECT DATE(created_at) AS businessDate, COUNT(*) AS usedCount
              FROM coupon_usage_history
             WHERE store_id = :storeId AND created_at >= :fromInclusive AND created_at < :toExclusive
             GROUP BY DATE(created_at)
             ORDER BY DATE(created_at)
            """, nativeQuery = true)
    List<DailyUsageCount> findDailyUsageCounts(@Param("storeId") long storeId,
            @Param("fromInclusive") LocalDateTime fromInclusive, @Param("toExclusive") LocalDateTime toExclusive);

    interface DailyUsageCount {
        LocalDate getBusinessDate();
        long getUsedCount();
    }
    // 발급일과 현재 쿠폰 상태에 관계없이 사용한 날짜의 기록만 회원별로 묶는다.
    @Query("""
            SELECT NEW com.launchcatch.coupon.contract.DailyMemberCouponStats(
                coupon.memberId, COUNT(history.id), SUM(history.discountAmount))
              FROM CouponUsageHistory history
              JOIN MemberCoupon coupon ON coupon.id = history.memberCouponId
             WHERE history.createdAt >= :fromInclusive AND history.createdAt < :toExclusive
             GROUP BY coupon.memberId
             ORDER BY coupon.memberId
            """)
    List<DailyMemberCouponStats> findDailyMemberStats(@Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive);
}
