package com.launchcatch.coupon.service.model;

import java.time.LocalDateTime;

// 묶음에 담는 발급 요청 한 건이며 requestTime은 요청이 대기열에 들어간 시각이다.
public record CouponIssuanceRequest(long memberId, long eventId, LocalDateTime requestTime) { }
