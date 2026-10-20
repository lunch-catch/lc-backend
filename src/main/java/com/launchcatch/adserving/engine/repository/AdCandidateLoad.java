package com.launchcatch.adserving.engine.repository;

import java.time.LocalDateTime;
import java.util.List;

// 00:00 적재의 쓰기다. 같은 영업일로 다시 불려도 행이 늘지 않게 upsert 한다
public interface AdCandidateLoad {

    // readAt 은 그 적재가 캠페인과 가게를 읽기 시작한 시각이다. 이보다 늦게 읽은 적재가 이미 쓴 행의 스냅샷 값은 바꾸지 않는다
    void upsertAll(List<CandidateRow> rows, LocalDateTime readAt, LocalDateTime now);
}
