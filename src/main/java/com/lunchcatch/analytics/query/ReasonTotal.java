package com.lunchcatch.analytics.query;

import com.lunchcatch.analytics.batch.InvalidReasonCode;

// 조회 조건에 맞는 무효 노출의 사유별 합계다
public record ReasonTotal(InvalidReasonCode reasonCode, long count) {
}
