package com.lunchcatch.analytics.query;

/*
 * 퍼널 시계열의 한 구간이다. 집계 전(IN_PROGRESS)이면 수치는 모두 null 이다. 집계된 0 과 구별하려는
 * 것이다. validRatio 는 노출이 하나도 없으면 null 이다.
 */
public record FunnelBucket(
        String label,
        AggregationStatus status,
        Long validImpressionCount,
        Long invalidImpressionCount,
        Double validRatio,
        Long wishCount,
        Long issueCount,
        Long redeemCount
) {
}
