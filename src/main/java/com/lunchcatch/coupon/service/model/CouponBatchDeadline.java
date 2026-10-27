package com.lunchcatch.coupon.service.model;

import com.lunchcatch.coupon.exception.CouponErrorCode;
import com.lunchcatch.coupon.exception.CouponException;

// 취소와 커밋 시작을 같은 잠금으로 판정하며 커밋이 시작되면 실제 결과를 기다린다.
public final class CouponBatchDeadline {

    private final long deadlineNanos;
    private boolean cancelled;
    private boolean committing;

    public CouponBatchDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public synchronized boolean cancel() {
        if (committing) {
            return false;
        }
        cancelled = true;
        return true;
    }

    public synchronized void check() {
        if (cancelled || deadlineNanos - System.nanoTime() <= 0) {
            throw new CouponException(CouponErrorCode.ISSUANCE_BUSY);
        }
    }

    public synchronized void beginCommit() {
        check();
        committing = true;
    }
}
