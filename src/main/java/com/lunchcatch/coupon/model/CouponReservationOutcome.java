package com.lunchcatch.coupon.model;

import com.lunchcatch.coupon.exception.CouponErrorCode;

// 순번 예약 한 건의 결과이며 성공하면 reservation을, 실패하면 거절 코드를 채운다.
public record CouponReservationOutcome(CouponReservation reservation, CouponErrorCode rejection, boolean recoveryRequired, boolean awaitingReservation) {

    public CouponReservationOutcome(CouponReservation reservation, CouponErrorCode rejection, boolean recoveryRequired) {
        this(reservation, rejection, recoveryRequired, false);
    } }
