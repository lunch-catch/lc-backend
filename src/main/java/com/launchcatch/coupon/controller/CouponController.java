package com.launchcatch.coupon.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.service.CouponCommandService;
import com.launchcatch.coupon.service.CouponLookUpService;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class CouponController {

    private final CouponLookUpService couponLookUpService;
    private final CouponCommandService couponCommandService;

    // 인증된 회원의 쿠폰을 필터와 커서로 조회한다.
    @GetMapping("/coupons")
    public ResponseEnvelope<CursorPageResponse<CouponDetailResponse>> getCoupons(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(required = false) String filter,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String pageToken) {
        return ResponseEnvelope.success(couponLookUpService.getCoupons(user.getId(), filter, pageSize, pageToken));
    }

    /**
     * 인증된 회원의 오늘 일일 발급 횟수와 일일 발급 한도를 조회한다.
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

    /**
     * 본인 쿠폰의 스냅샷과 조회 시점 상태, 사용 가능 여부 및 사용 결과를 조회한다.
     *
     * @param user 인증된 회원 정보
     * @param memberCouponId 조회할 회원 쿠폰의 ID
     * @return 쿠폰의 상세 표시 정보와 사용 결과를 담은 성공 응답
     */
    @GetMapping("/coupons/{memberCouponId}")
    public ResponseEnvelope<CouponDetailResponse> getDetail(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable @Positive Long memberCouponId) {
        return ResponseEnvelope.success(couponLookUpService.getDetail(user.getId(), memberCouponId));
    }

    /**
     * 본인의 미사용 쿠폰을 소프트 삭제하고 기존 QR을 무효화한다.
     *
     * @param user 인증된 회원 정보
     * @param memberCouponId 삭제할 회원 쿠폰의 ID
     * @return 응답 본문 없는 HTTP 204 응답
     */
    @DeleteMapping("/coupons/{memberCouponId}")
    public ResponseEntity<Void> deleteCoupon(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable @Positive Long memberCouponId) {
        couponCommandService.deleteCoupon(user.getId(), memberCouponId);
        return ResponseEntity.noContent().build();
    }
}
