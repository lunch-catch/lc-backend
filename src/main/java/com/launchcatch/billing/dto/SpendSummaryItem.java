package com.launchcatch.billing.dto;

import java.time.LocalDate;

/*
 * 소진 집계 한 줄이다. 묶는 기준에 들지 않은 date 와 campaignId 는 null 이다.
 * spentPoint 와 reservedPoint 는 크기이므로 항상 0 이상이다.
 */
public record SpendSummaryItem(
        LocalDate date,
        Long campaignId,
        long spentPoint,
        long reservedPoint,
        long impressionCount
) {
}
