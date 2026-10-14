package com.launchcatch.adserving.feed;

import com.launchcatch.adserving.engine.SlotType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/*
 * 카드 한 장을 누구에게 언제 내려줬는지의 기록이다.
 * 노출 검증은 Valkey 에 둔 이 기록으로 하고 serve_log 에는 응답 뒤에 비동기로 남긴다.
 */
public record ServeRecord(
        UUID serveId,
        UUID feedId,
        Long memberId,
        LocalDate businessDate,
        Long campaignId,
        Long storeId,
        SlotType slotType,
        int slotPosition,
        LocalDateTime servedAt
) {
}
