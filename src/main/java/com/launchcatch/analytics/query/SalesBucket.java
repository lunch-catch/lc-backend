package com.launchcatch.analytics.query;

/*
 * 매출 시계열의 한 구간이다. 집계 전(IN_PROGRESS)이면 금액은 모두 null 이다. 집계된 0 과 구별하려는
 * 것이다.
 */
public record SalesBucket(
        String label,
        AggregationStatus status,
        Long chargeAmount,
        Long spentAmount,
        Long refundAmount,
        Long adjustAmount,
        Long unspentBalance
) {
}
