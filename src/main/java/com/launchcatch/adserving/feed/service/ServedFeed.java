package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.feed.repository.ServeRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/*
 * 피드 요청 1회를 서빙 로그와 접속 위치로 남기려고 대기열에 넣는 묶음이다.
 * 카드가 없는 요청도 접속 위치는 남겨 feedId 가 null 이고 cards 가 비어 있다.
 * requestedAt 은 요청을 받은 한국 시각이다. 작업자가 시계를 읽지 않게 요청이 넣어 보낸다(#379).
 */
public record ServedFeed(
        LocalDate businessDate,
        Long memberId,
        UUID feedId,
        double latitude,
        double longitude,
        List<ServeRecord> cards,
        LocalDateTime requestedAt
) {
}
