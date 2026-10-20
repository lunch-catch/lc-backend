package com.launchcatch.adserving.engine.repository;

import java.time.LocalDateTime;
import java.util.List;

// 00:00 적재의 쓰기다. 같은 영업일로 다시 불려도 행이 늘지 않게 upsert 한다
public interface AdCandidateLoad {

    void upsertAll(List<CandidateRow> rows, LocalDateTime now);
}
