package com.launchcatch.analytics.query;

import com.launchcatch.analytics.batch.InvalidReasonCode;

// 기간 안에 회원 하나가 캠페인 하나로 보낸 사유별 무효 노출 합계다
public record InvalidImpressionRow(long memberId, long campaignId, InvalidReasonCode reasonCode, long count) {
}
