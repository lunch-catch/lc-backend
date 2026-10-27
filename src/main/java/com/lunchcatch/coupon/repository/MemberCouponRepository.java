package com.lunchcatch.coupon.repository;

import com.lunchcatch.coupon.dto.CouponSummaryResponse;
import com.lunchcatch.coupon.entity.MemberCoupon;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long>, MemberCouponRepositoryCustom {

    // 본인 쿠폰을 잠가 같은 쿠폰의 삭제와 QR 갱신, 만료 저장을 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberCoupon> findByIdAndMemberId(Long id, Long memberId);

    // 잠금 전 조회가 엔티티를 영속성 컨텍스트에 남겨 최신 상태 조회를 가리지 않도록 값만 반환한다.
    @Query("SELECT coupon.id AS id, coupon.qrVersion AS qrVersion FROM MemberCoupon coupon WHERE coupon.qrToken = :token")
    Optional<QrReference> findQrReference(@Param("token") UUID token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT coupon FROM MemberCoupon coupon WHERE coupon.id = :id")
    Optional<MemberCoupon> findByIdForUpdate(@Param("id") Long id);

    interface QrReference {
        Long getId();
        int getQrVersion();
    }

    // 삭제한 쿠폰도 포함해 요청한 회원이 이미 발급받은 이벤트 ID를 일괄 조회한다.
    @Query("""
            SELECT coupon.couponEventId FROM MemberCoupon coupon
             WHERE coupon.memberId = :memberId AND coupon.couponEventId IN :eventIds
            """)
    List<Long> findIssuedEventIds(@Param("memberId") Long memberId, @Param("eventIds") List<Long> eventIds);

    // 소진 복구에도 삭제한 쿠폰을 포함해 원래 마지막 발급 시각을 사용한다.
    @Query("SELECT MAX(coupon.createdAt) FROM MemberCoupon coupon WHERE coupon.couponEventId = :eventId")
    Optional<LocalDateTime> findLastIssuedAtByCouponEventId(@Param("eventId") Long eventId);


    // 상태로 제외하지 않아 삭제된 쿠폰도 포함하며, 사용 이력 조인으로 쿠폰 수가 중복되지 않도록 집계한다.
    @Query("""
            SELECT NEW com.lunchcatch.coupon.dto.CouponSummaryResponse(
                COUNT(DISTINCT coupon.id),
                COUNT(DISTINCT history.memberCouponId),
                COALESCE(SUM(history.discountAmount), 0L))
              FROM MemberCoupon coupon
              LEFT JOIN CouponUsageHistory history ON history.memberCouponId = coupon.id
             WHERE coupon.memberId = :memberId
            """)
    CouponSummaryResponse findSummaryByMemberId(@Param("memberId") Long memberId);
}
