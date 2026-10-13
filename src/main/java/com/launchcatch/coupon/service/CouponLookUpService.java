package com.launchcatch.coupon.service;

import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponUsageHistory;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponUsageHistoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponLookUpService {

    private final MemberCouponRepository memberCouponRepository;
    private final CouponEventRepository couponEventRepository;
    private final CouponUsageHistoryRepository couponUsageHistoryRepository;
    private final CouponDailyLimitRepository couponDailyLimitRepository;
    private final Clock clock;

    @Value("${coupon.daily-limit}")
    private int dailyLimit;

    /**
     * 오늘의 일일 한도에 반영된 쿠폰 수와 설정된 일일 한도를 조회한다.
     * 발급 기록이 없으면 발급 횟수는 0이며, 발급 횟수가 한도 이상이면 한도에 도달한 것으로 반환한다.
     *
     * @param memberId 인증된 회원의 ID
     * @return 조회 영업일, 일일 한도에 반영된 쿠폰 수, 일일 한도와 한도 도달 여부
     */
    public CouponIssuanceLimitResponse getIssuanceLimit(Long memberId) {
        LocalDate businessDate = LocalDate.now(clock.withZone(ClockConfig.ZONE));
        int issuedCount = couponDailyLimitRepository
                .findIssuedCountByMemberIdAndBusinessDate(memberId, businessDate)
                .orElse(0);
        return new CouponIssuanceLimitResponse(
                businessDate, issuedCount, dailyLimit, issuedCount >= dailyLimit);
    }

    /**
     * 본인 쿠폰의 이벤트 스냅샷과 조회 시점의 상태, 사용 가능 여부 및 사용 결과를 조회한다.
     * 소유권을 확인할 수 없으면 접근을 거절하고, 본인 소유이지만 삭제된 쿠폰은 조회할 수 없다.
     * 미사용 쿠폰의 사용 기간이 끝났으면 만료 상태로 변경해 저장한다.
     *
     * @param memberId 인증된 회원의 ID
     * @param memberCouponId 조회할 회원 쿠폰의 ID
     * @return 가게와 할인 스냅샷, 쿠폰 상태, 사용 가능 시간 및 사용 완료 결과
     * @throws CouponException 본인 소유권을 확인할 수 없거나 쿠폰이 삭제됐거나 연결된 스냅샷 또는 사용 이력이 누락된 경우
     */
    @Transactional
    public CouponDetailResponse getDetail(Long memberId, Long memberCouponId) {
        MemberCoupon coupon = findOwnedCoupon(memberId, memberCouponId);
        CouponEvent event = couponEventRepository.findById(coupon.getCouponEventId())
                .orElseThrow(() -> missingCouponData(CouponErrorCode.EVENT_SNAPSHOT_MISSING, coupon));
        LocalDateTime now = LocalDateTime.now(clock.withZone(ClockConfig.ZONE));
        MemberCouponStatus state = resolveState(coupon, now);
        boolean usable = isUsable(coupon, state, now);
        CouponUsageHistory history = findUsageHistory(coupon, state);

        return CouponDetailResponse.from(coupon, event, state, usable, history);
    }

    /**
     * 회원의 누적 쿠폰 발급 수, 사용 수와 할인받은 금액의 합계를 조회한다.
     * 삭제한 쿠폰도 발급 수에 포함하며, 할인액은 사용 이력에 확정해 저장한 금액을 기준으로 한다.
     *
     * @param memberId 인증된 회원의 ID
     * @return 누적 발급 수, 사용 수와 할인액 합계이며, 이력이 없으면 각 값은 0
     */
    public CouponSummaryResponse getSummary(Long memberId) {
        return memberCouponRepository.findSummaryByMemberId(memberId);
    }

    // 본인 소유 쿠폰을 조회하고 소유권 확인 실패와 삭제된 쿠폰을 구분해 거절한다.
    private MemberCoupon findOwnedCoupon(Long memberId, Long memberCouponId) {
        MemberCoupon coupon = memberCouponRepository.findByIdAndMemberId(memberCouponId, memberId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_ACCESS_DENIED));
        if (coupon.getStatus() == MemberCouponStatus.REMOVED) {
            throw new CouponException(CouponErrorCode.COUPON_NOT_FOUND);
        }
        return coupon;
    }

    // 미사용 쿠폰의 사용 종료 시각이 지났으면 엔티티 상태를 만료로 변경한다.
    private MemberCouponStatus resolveState(MemberCoupon coupon, LocalDateTime now) {
        coupon.changeToExpired(now);
        return coupon.getStatus();
    }

    // 쿠폰이 미사용 상태이고 조회 시각이 사용 가능 시간 안인지 확인한다.
    private boolean isUsable(MemberCoupon coupon, MemberCouponStatus state, LocalDateTime now) {
        return state == MemberCouponStatus.ISSUED
                && !now.isBefore(coupon.getUsableStartAt())
                && now.isBefore(coupon.getUsableEndAt());
    }

    // 사용 완료 쿠폰의 확정된 사용 이력을 조회한다.
    private CouponUsageHistory findUsageHistory(MemberCoupon coupon, MemberCouponStatus state) {
        if (state != MemberCouponStatus.USED) {
            return null;
        }
        return couponUsageHistoryRepository.findByMemberCouponId(coupon.getId())
                .orElseThrow(() -> missingCouponData(CouponErrorCode.USAGE_HISTORY_MISSING, coupon));
    }

    // 정합성 오류의 원인을 추적할 식별자는 서버 로그에만 남긴다.
    private CouponException missingCouponData(CouponErrorCode errorCode, MemberCoupon coupon) {
        log.error("coupon data integrity error. code={}, memberCouponId={}, eventId={}",
                errorCode.getCode(), coupon.getId(), coupon.getCouponEventId());
        return new CouponException(errorCode);
    }

}
