package com.launchcatch.coupon.service;

import java.time.LocalDateTime;

public record CouponIssuedEvent(Long couponId, Long campaignId, LocalDateTime issuedAt) {
}
