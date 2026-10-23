package com.launchcatch.coupon;

public record CouponConsistencyFinding(String type, long targetId, long expectedValue, long actualValue) { }
