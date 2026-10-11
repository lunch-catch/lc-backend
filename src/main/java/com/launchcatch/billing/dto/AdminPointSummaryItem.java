package com.launchcatch.billing.dto;

/*
 * 구간 하나의 합계다. 금액은 모두 크기이고 조정액만 부호가 있다.
 * spentAmount 는 과오 차감 정정을 이미 뺀 값이라 정정이 들어온 구간에서는 0 보다 작아질 수 있다.
 */
public record AdminPointSummaryItem(
        String label,
        long chargeAmount,
        long spentAmount,
        long reservedAmount,
        long releasedAmount,
        long refundAmount,
        long adjustAmount
) {
}
