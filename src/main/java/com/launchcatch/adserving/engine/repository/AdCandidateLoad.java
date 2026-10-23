package com.launchcatch.adserving.engine.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/*
 * 00:00 적재와 예산 갱신의 쓰기다.
 * 같은 영업일로 다시 불려도 행이 늘지 않고 결과가 같다.
 */
public interface AdCandidateLoad {

    // readAt 은 그 적재가 캠페인과 가게를 읽기 시작한 시각이다. 이보다 늦게 읽은 적재가 이미 쓴 행의 스냅샷 값은 바꾸지 않는다
    void upsertAll(List<CandidateRow> rows, LocalDateTime readAt, LocalDateTime now);

    /*
     * 그날 후보의 예약액과 단가를 덮는다(#837).
     * 같은 값으로 다시 불려도 결과가 같다.
     */
    void updateBudgets(LocalDate businessDate, List<CandidateBudget> budgets, LocalDateTime now);
}
