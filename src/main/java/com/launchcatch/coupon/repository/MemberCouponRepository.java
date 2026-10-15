package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.entity.MemberCoupon;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long>, MemberCouponRepositoryCustom {

    // 본인 쿠폰을 잠가 같은 쿠폰의 삭제와 QR 갱신, 만료 저장을 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberCoupon> findByIdAndMemberId(Long id, Long memberId);

    // 삭제한 쿠폰도 포함해 요청한 회원이 이미 발급받은 이벤트 ID를 일괄 조회한다.
    @Query("""
            SELECT coupon.couponEventId FROM MemberCoupon coupon
             WHERE coupon.memberId = :memberId AND coupon.couponEventId IN :eventIds
            """)
    List<Long> findIssuedEventIds(@Param("memberId") Long memberId, @Param("eventIds") List<Long> eventIds);

    // 삭제된 쿠폰도 발급 기록으로 남아 같은 이벤트에서 다시 발급받을 수 없다.
    boolean existsByCouponEventIdAndMemberId(Long couponEventId, Long memberId);

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
