package com.launchcatch.coupon.service.model;

import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.exception.CouponErrorCode;

// 요청별 발급 결과이며 recoveryRequired는 순번을 못 받은 요청을 복구 후 한 번 재접수할 수 있는지 구분한다.
public record CouponIssuanceOutcome(
        CouponIssueResponse response,
        CouponReservation reservation,
        CouponErrorCode rejection,
        boolean recoveryRequired
) {

    // 발급에 성공한 결과를 만든다.
    public static CouponIssuanceOutcome issued(CouponIssueResponse response, CouponReservation reservation) {
        return new CouponIssuanceOutcome(response, reservation, null, false);
    }

    // 업무 조건으로 거절한 결과를 만든다.
    public static CouponIssuanceOutcome rejected(CouponErrorCode rejection) {
        return new CouponIssuanceOutcome(null, null, rejection, false);
    }

    // 순번 예약을 받지 못한 요청만 복구 후 재접수할 수 있게 구분한다.
    public static CouponIssuanceOutcome requiringRecovery() {
        return new CouponIssuanceOutcome(null, null, CouponErrorCode.ISSUANCE_BUSY, true);
    }
}
