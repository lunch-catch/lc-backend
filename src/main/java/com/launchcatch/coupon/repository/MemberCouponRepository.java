package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.entity.MemberCoupon;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long>, MemberCouponRepositoryCustom {

    // 본인 쿠폰을 잠가 같은 쿠폰의 삭제 요청을 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberCoupon> findByIdAndMemberId(Long id, Long memberId);

    // 상태로 제외하지 않아 삭제된 쿠폰도 포함하며, 사용 이력 조인으로 쿠폰 수가 중복되지 않도록 집계한다.
    @Query("""
            SELECT NEW com.launchcatch.coupon.dto.CouponSummaryResponse(
                COUNT(DISTINCT coupon.id),
                COUNT(DISTINCT history.memberCouponId),
                COALESCE(SUM(history.discountAmount), 0L))
              FROM MemberCoupon coupon
              LEFT JOIN CouponUsageHistory history ON history.memberCouponId = coupon.id
             WHERE coupon.memberId = :memberId
            """)
    CouponSummaryResponse findSummaryByMemberId(@Param("memberId") Long memberId);
}
