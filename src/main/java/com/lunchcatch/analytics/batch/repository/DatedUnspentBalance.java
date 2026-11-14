package com.lunchcatch.analytics.batch.repository;

import java.time.LocalDate;

// daily_settlement_analytics 에 저장된 어느 영업일 말의 미소진 잔액이다
public record DatedUnspentBalance(
        LocalDate businessDate,
        long unspentBalance
) {
}
