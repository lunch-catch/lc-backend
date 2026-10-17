package com.launchcatch.analytics.batch.service;

import java.time.LocalDate;

/*
 * 하루 집계의 한 단계다. 하루 결과는 모으기와 공개로 나뉜다.
 *
 * collect 는 트랜잭션 없이 원천 도메인의 집계 조회와 event_log 를 읽어 결과를 만든다. 자기 테이블은
 * 쓰지 않는다. publish 는 실행기가 연 한 트랜잭션 안에서 자기 테이블의 그날 행을 교체하고 쓴 행
 * 수를 돌려준다. 재시도는 모으기부터 다시 하므로 collect 는 부작용이 없어야 한다.
 */
public interface AggregationStep<R> {

    /** 지표의 step 라벨이다. 회원이나 캠페인 id 를 넣지 않는다. */
    String name();

    R collect(LocalDate businessDate);

    int publish(LocalDate businessDate, R collected);
}
