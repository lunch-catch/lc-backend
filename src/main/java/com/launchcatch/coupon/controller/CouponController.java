package com.launchcatch.coupon.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.service.CouponLookUpService;
import com.launchcatch.global.response.ResponseEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class CouponController {

    private final CouponLookUpService couponLookUpService;

    /**
     * 인증된 회원의 오늘 누적 발급 횟수와 일일 발급 한도를 조회한다.
     *
     * @param user 인증된 회원 정보
     * @return 영업일과 발급 횟수, 일일 한도 및 한도 도달 여부를 담은 성공 응답
     */
    @GetMapping("/coupons/issuance-limit")
    public ResponseEnvelope<CouponIssuanceLimitResponse> getIssuanceLimit(
            @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(couponLookUpService.getIssuanceLimit(user.getId()));
    }

    /**
     * 인증된 회원의 누적 쿠폰 발급 수와 사용 수, 할인받은 금액의 합계를 조회한다.
     *
     * @param user 인증된 회원 정보
     * @return 누적 발급 수와 사용 수, 할인액 합계를 담은 성공 응답
     */
    @GetMapping("/coupons/summary")
    public ResponseEnvelope<CouponSummaryResponse> getSummary(@AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(couponLookUpService.getSummary(user.getId()));
    }
}
