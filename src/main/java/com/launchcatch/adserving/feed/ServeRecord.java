package com.launchcatch.adserving.feed;

import com.launchcatch.adserving.engine.SlotType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/*
 * 내려준 카드 한 장의 serve 기록이다.
 * 인메모리 캐시에 30분 두어 노출 검증이 읽고, 응답 뒤 serve_log 에 한 행으로 남는다.
 */
public record ServeRecord(
        UUID serveId,
        UUID feedId,
        Long memberId,
        LocalDate businessDate,
        long campaignId,
        long storeId,
        SlotType slotType,
        int slotPosition,
        LocalDateTime servedAt
) {
}
