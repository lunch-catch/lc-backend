package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.contract.DailyMemberCouponStats;
import com.launchcatch.coupon.entity.CouponUsageHistory;
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
