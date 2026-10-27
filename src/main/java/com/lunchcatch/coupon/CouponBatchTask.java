package com.lunchcatch.coupon;

public enum CouponBatchTask {
    PREPARE, QUEUE_PREPARE, OPEN, CLOSE, EXPIRE, VERIFY;

    public String jobName() {
        return "COUPON_" + name();
    }
}
