package com.launchcatch.analytics.query;

// 캠페인 하나의 조회 기간 소진액과 누적 유효 노출 합이다
public record CampaignReportTotal(long campaignId, long spentAmount, long validImpressionCount) {
}
