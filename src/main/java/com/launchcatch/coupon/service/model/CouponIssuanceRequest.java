package com.launchcatch.coupon.service.model;

import java.time.LocalDateTime;

// 묶음에 담는 발급 요청 한 건이며 requestTime은 발급 조정자가 최초로 접수한 시각이다.
public record CouponIssuanceRequest(long memberId, long eventId, LocalDateTime requestTime) { }
