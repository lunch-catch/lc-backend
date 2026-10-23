package com.launchcatch.coupon.model;

import com.launchcatch.coupon.dto.CouponIssueResponse;

// 요청 스레드에 돌려줄 발급 응답과 Redis 예약 완료로 확인한 소진 여부다.
public record CouponIssuanceResult(CouponIssueResponse response, boolean soldOut) { }
