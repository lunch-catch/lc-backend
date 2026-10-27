package com.lunchcatch.billing.repository;

import com.lunchcatch.billing.entity.EntryType;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 점주에게 보이는 원장 한 줄이다. 노출 식별자와 변동 전후 잔액은 담지 않는다
public record LedgerRow(
        Long id,
        EntryType entryType,
        Long amount,
        Long campaignId,
        LocalDate businessDate,
        Long paymentId,
        LocalDateTime createdAt
) {
}
