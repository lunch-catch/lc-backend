package com.lunchcatch.analytics.query.repository;

import com.lunchcatch.analytics.batch.InvalidReasonCode;
import com.lunchcatch.analytics.query.FraudDateRange;

/*
 * A-09 의 조회 조건이다.
 * 사유, 캠페인, 회원은 비어 있으면 거르지 않는다
 */
public record FraudFilter(FraudDateRange range, InvalidReasonCode reasonCode, Long campaignId, Long memberId) {
}
