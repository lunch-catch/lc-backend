package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.contract.MemberCouponStats;
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

    // 회원의 실제 사용일 범위에서 확정된 할인액을 합산하며 발급일로 제한하지 않는다.
    @Query("""
            SELECT NEW com.launchcatch.coupon.contract.MemberCouponStats(
                COUNT(history.id), COALESCE(SUM(history.discountAmount), 0L))
              FROM CouponUsageHistory history
              JOIN MemberCoupon coupon ON coupon.id = history.memberCouponId
             WHERE coupon.memberId = :memberId
               AND history.createdAt >= :fromInclusive AND history.createdAt < :toExclusive
            """)
    MemberCouponStats findMemberStats(@Param("memberId") long memberId,
            @Param("fromInclusive") LocalDateTime fromInclusive, @Param("toExclusive") LocalDateTime toExclusive);
}
