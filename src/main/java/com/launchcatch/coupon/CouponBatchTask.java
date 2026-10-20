package com.launchcatch.coupon;

public enum CouponBatchTask {
    PREPARE, OPEN, CLOSE, EXPIRE, VERIFY;

    // 조회 페이지와 JDBC INSERT의 상한이며 회차별·쿠폰별 커밋 단위와는 별개다.
    public static final int BATCH_SIZE = 1_000;

    public String jobName() {
        return "COUPON_" + name();
    }
}
