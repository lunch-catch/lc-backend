package com.lunchcatch.analytics.batch;

/*
 * 하루 퍼널 지표다. 노출은 광고 서빙 집계 조회, 발급과 사용은 쿠폰 집계 조회, 찜은 분석 자기 event_log 에서
 * 온다. 무효 노출은 EXPIRED_OR_UNKNOWN 과 NOT_OWNER 의 합이다.
 */
public record FunnelMetrics(
        long validImpressionCount,
        long invalidImpressionCount,
        long wishCount,
        long issueCount,
        long redeemCount
) {
}
