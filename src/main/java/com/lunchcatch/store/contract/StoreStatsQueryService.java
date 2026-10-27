package com.lunchcatch.store.contract;

import java.time.LocalDate;

// 분석의 일별 집계 배치가 사용하는 가게 집계 조회다.
public interface StoreStatsQueryService {
    FinalizedStoreCounts finalizedStoreCounts(LocalDate businessDate);
}