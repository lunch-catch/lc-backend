package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.entity.EntryType;
import com.lunchcatch.billing.entity.LedgerSource;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * 관리자에게 내리는 원장 한 줄이다. 여섯 종류가 모두 들어온다.
 * balanceBefore 와 balanceAfter 는 저장값이 아니라 누적으로 계산한 값이다.
 */
public record AdminLedgerItem(
        Long ledgerId,
        Long ownerId,
        EntryType entryType,
        long balanceBefore,
        long amount,
        long balanceAfter,
        Long campaignId,
        UUID serveId,
        String pgTid,
        LedgerSource source,
        String reason,
        OffsetDateTime createdAt
) {
}
