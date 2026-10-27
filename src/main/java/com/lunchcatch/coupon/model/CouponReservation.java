package com.lunchcatch.coupon.model;

// 이벤트의 재고 순번 하나를 예약한 결과이며 id는 완료와 반납 때 같은 예약인지 확인하는 값이다.
public record CouponReservation(long eventId, int sequence, String id) { }
