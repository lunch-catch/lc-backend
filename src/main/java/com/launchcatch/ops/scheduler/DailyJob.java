package com.launchcatch.ops.scheduler;

import java.time.LocalDate;

/*
 * 00:00 묶음의 한 단계다. 각 도메인이 구현해 등록한다 (설계 문서 1.1절).
 *
 * 스케줄러는 순서와 멱등만 책임지고 작업 내용은 모른다 (규칙 6). 그래서 이 모듈은 어떤 업무
 * 도메인도 의존하지 않고, 의존은 도메인 -> ops 한 방향으로 남는다.
 *
 * 실행 순서는 구현 클래스에 @Order 를 붙여 정한다. 앞 단계가 실패하면 뒤 단계는 돌지 않는다.
 * 같은 영업일로 두 번 불릴 수 있으므로 구현은 멱등이어야 한다. 이어받기와 수동 재실행이
 * 그 경우다.
 */
public interface DailyJob {

    /** batch_execution_log.job_name 에 그대로 들어간다. 50자 안이고 영업일을 붙이지 않는다. */
    String jobName();

    void run(LocalDate businessDate);
}
