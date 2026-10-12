package com.launchcatch.coupon.service;

import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CouponCommandService {

    private final MemberCouponRepository memberCouponRepository;
    private final CouponDailyLimitRepository couponDailyLimitRepository;

    /**
     * 본인 쿠폰의 행 잠금을 확보하고 소프트 삭제하며 이미 삭제된 본인 쿠폰의 재요청은 허용한다.
     * 처음 삭제한 쿠폰은 발급받은 날짜의 일일 발급 횟수를 1 줄이며 발급 기록과 재고는 유지한다.
     *
     * @param memberId 인증된 회원의 ID
     * @param memberCouponId 삭제할 회원 쿠폰의 ID
     * @throws CouponException 본인 소유권을 확인할 수 없거나 사용 완료된 쿠폰인 경우
     */
    public void deleteCoupon(Long memberId, Long memberCouponId) {
        MemberCoupon coupon = findOwnedCoupon(memberId, memberCouponId);
        if (!coupon.remove()) {
            return;
        }
        LocalDate businessDate = coupon.getCreatedAt().toLocalDate();
        couponDailyLimitRepository.findByMemberIdAndBusinessDate(memberId, businessDate)
                .ifPresent(dailyLimit -> dailyLimit.decreaseIssuedCount());
    }

    // 본인 쿠폰의 행 잠금을 확보하고 소유권을 확인할 수 없으면 접근을 거절한다.
    private MemberCoupon findOwnedCoupon(Long memberId, Long memberCouponId) {
        return memberCouponRepository.findByIdAndMemberId(memberCouponId, memberId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_ACCESS_DENIED));
    }
}
