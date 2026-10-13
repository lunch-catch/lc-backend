package com.launchcatch.adserving.feed;

import com.launchcatch.adserving.engine.AssignedCard;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/*
 * 한 번의 피드 응답으로 내려간 카드 묶음이다.
 * serve 기록 캐시, serve_log, 일별 접속 위치가 모두 이 값으로 쓰인다.
 */
public record ServedFeed(
        UUID feedId,
        Long memberId,
        LocalDate businessDate,
        LocalDateTime servedAt,
        double latitude,
        double longitude,
        List<ServedCard> cards
) {

    public ServedFeed {
        cards = List.copyOf(cards);
    }

    // 카드 1장과 그 카드의 식별자다
    public record ServedCard(UUID serveId, AssignedCard card) {
    }
}
