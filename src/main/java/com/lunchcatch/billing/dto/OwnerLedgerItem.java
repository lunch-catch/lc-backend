package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.entity.EntryType;
import java.time.LocalDate;
import java.time.OffsetDateTime;

// 점주에게 내리는 원장 한 줄이다. DEDUCT 는 내리지 않으므로 노출 식별자도 없다
public record OwnerLedgerItem(
        Long ledgerId,
        EntryType entryType,
        long amount,
        Long campaignId,
        LocalDate businessDate,
        Long paymentId,
        OffsetDateTime createdAt
) {
}
