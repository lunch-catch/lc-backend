package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.LedgerSource;
import java.time.LocalDateTime;
import java.util.UUID;

// 관리자에게 보이는 원장 한 줄이다. pgTid 는 결제와 이어서 가져온다
public record AdminLedgerRow(
        Long id,
        Long ownerId,
        EntryType entryType,
        Long amount,
        Long campaignId,
        UUID serveId,
        String pgTid,
        LedgerSource source,
        String reason,
        LocalDateTime createdAt
) {
}
