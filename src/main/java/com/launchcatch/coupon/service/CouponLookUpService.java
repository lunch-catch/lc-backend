package com.launchcatch.coupon.service;

import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponLookUpService {

    private final CouponDailyLimitRepository couponDailyLimitRepository;
    private final Clock clock;

    @Value("${coupon.daily-limit}")
    private int dailyLimit;

    /**
     * 오늘의 누적 쿠폰 발급 횟수와 설정된 일일 한도를 조회한다.
     * 발급 기록이 없으면 발급 횟수는 0이며, 발급 횟수가 한도 이상이면 한도에 도달한 것으로 반환한다.
     *
     * @param memberId 인증된 회원의 ID
     * @return 조회 영업일, 누적 발급 횟수, 일일 한도와 한도 도달 여부
     */
    public CouponIssuanceLimitResponse getIssuanceLimit(Long memberId) {
        LocalDate businessDate = LocalDate.now(clock.withZone(ClockConfig.ZONE));
        int issuedCount = couponDailyLimitRepository
                .findIssuedCountByMemberIdAndBusinessDate(memberId, businessDate)
                .orElse(0);
        return new CouponIssuanceLimitResponse(
                businessDate, issuedCount, dailyLimit, issuedCount >= dailyLimit);
    }
}
