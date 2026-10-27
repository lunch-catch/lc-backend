package com.launchcatch.coupon;

// Redis Lua와 Java가 공유하는 고정 프로토콜이며 예약 순번은 양수다.
public enum CouponSequenceCode {
    BLOCKED(-1), SOLD_OUT(0), AVAILABLE(1), PENDING(2), NO_AVAILABLE_SEQUENCE(-2);

    private final int code;

    CouponSequenceCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
