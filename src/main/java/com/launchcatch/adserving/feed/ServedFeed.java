package com.launchcatch.adserving.feed;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/*
 * 피드 요청 1회를 서빙 로그와 접속 위치로 남기려고 대기열에 넣는 묶음이다.
 * 카드가 없는 요청도 접속 위치는 남겨 feedId 가 null 이고 cards 가 비어 있다.
 */
public record ServedFeed(
        LocalDate businessDate,
        Long memberId,
        UUID feedId,
        double latitude,
        double longitude,
        List<ServeRecord> cards
) {
}
