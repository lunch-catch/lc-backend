package com.launchcatch.billing.dto;

import java.time.LocalDate;

/*
 * 점주의 잔액과 오늘 예약 중인 포인트다.
 * unspentTotal 은 balance 와 reservedPoint 의 합이며 아직 쓰지 않은 포인트 전체를 뜻한다.
 */
public record PointBalanceResponse(
        long balance,
        long reservedPoint,
        long unspentTotal,
        LocalDate businessDate
) {
}
