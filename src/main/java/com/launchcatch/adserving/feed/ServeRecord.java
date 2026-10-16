package com.launchcatch.adserving.feed;

import com.launchcatch.adserving.engine.SlotType;
import java.time.Instant;
import java.util.UUID;

// 카드 1장을 누구에게 어느 자리로 내려줬는지다. 노출 검증이 이 기록으로 유효와 무효를 가른다(FR-097).
public record ServeRecord(
        UUID serveId,
        Long memberId,
        Long campaignId,
        Long storeId,
        SlotType slotType,
        int slotPosition,
        Instant servedAt
) {
}
