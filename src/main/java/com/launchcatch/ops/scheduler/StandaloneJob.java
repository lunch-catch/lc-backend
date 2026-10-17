package com.launchcatch.ops.scheduler;

import java.time.LocalDate;

/*
 * 00:00 묶음(DailyJob) 밖에서 독자적으로 점유하는 작업이다. 각 도메인이 구현해 등록한다(#360).
 *
 * claim 과 점유 자체는 그 작업의 스케줄러가 직접 한다(KakaoUnlinkRetryScheduler 가 선례다).
 * 이 인터페이스는 오직 "멈춘 실행을 이어받았을 때 다시 돌릴 길" 을 등록하는 자리다.
 * DailyJobScheduler.takeOverStale 이 job_name 으로 찾아 run 을 다시 부른다. 등록되지 않은
 * 작업은 지금처럼 FAILED 로 닫고 알린다.
 *
 * run 은 이어받아 다시 도는 시도에서도 멱등해야 한다. attemptNo 는 이 시도의 실행 세대다.
 * 구현이 자기 결과를 공개하는 트랜잭션 안에서 ops.contract.BatchAttemptGuard 에 그대로
 * 넘겨, 그 트랜잭션을 커밋하는 순간에도 소유자와 세대가 여전히 자기 것인지 확인한다.
 */
public interface StandaloneJob {

    /** batch_execution_log.job_name 에 그대로 들어간다. 50자 안이고 영업일을 붙이지 않는다. */
    String jobName();

    void run(LocalDate businessDate, int attemptNo);
}
