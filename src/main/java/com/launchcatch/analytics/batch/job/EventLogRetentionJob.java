package com.launchcatch.analytics.batch.job;

import com.launchcatch.analytics.batch.service.EventLogRetentionService;
import com.launchcatch.global.logging.FailureLogDetails;
import com.launchcatch.ops.scheduler.StandaloneJob;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 매일 04:00 에 보존 기간이 지난 이벤트 로그를 지운다(I-03).
 *
 * 01:00 집계가 끝난 뒤의 새벽이다. 서빙 휴지 구간이 없어졌으므로 시각 자체에 대한 의존은 없고
 * 이벤트 기록 쓰기와 겹치는 시간을 줄이려는 선택이다.
 *
 * 배치 서버 두 대가 같은 시각에 시도하고, batch_execution_log 점유로 한 서버만 돈다.
 * @EnableScheduling 은 launchcatch.scheduler.enabled=true 인 배치 서버에서만 뜬다.
 *
 * 점유한 행은 반드시 닫는다. RUNNING 으로 남기면 2분 뒤 다른 서버가 이어받는다. 삭제는 멱등이라
 * 이어받은 서버가 다시 돌려도 남은 행만 지운다. 그 길이 StandaloneJob 이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventLogRetentionJob implements StandaloneJob {

    /** batch_execution_log.job_name 이다. 영업일은 붙이지 않는다. */
    public static final String JOB_NAME = "EVENT_LOG_RETENTION";

    private final EventLogRetentionService retention;
    private final BatchExecutionService executions;

    /*
     * 시간대를 값으로 적는다. 서버 기본 시간대로 두면 컨테이너가 UTC 로 뜨는 순간 낮에 돈다.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void purgeExpiredEvents() {
        LocalDate businessDate = executions.businessDate();
        if (!executions.claim(JOB_NAME, businessDate)) {
            log.info("event=EVENT_LOG_RETENTION_SKIPPED reason=ALREADY_CLAIMED businessDate={}", businessDate);
            return;
        }
        int deleted;
        try {
            deleted = retention.purge();
        } catch (RuntimeException failure) {
            try {
                executions.fail(JOB_NAME, businessDate, FailureLogDetails.from(failure).toString());
            } catch (RuntimeException recordFailure) {
                failure.addSuppressed(recordFailure);
            }
            // 공통 스케줄러 관측이 실패를 인식하고 마지막 성공 시각을 갱신하지 않게 한다.
            throw failure;
        }
        executions.succeed(JOB_NAME, businessDate, deleted);
    }

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate, int attemptNo) {
        runCounting(businessDate, attemptNo);
    }

    /*
     * 이어받은 서버와 관리자 수동 재실행이 부른다. 지운 건수를 돌려주면 DailyJobRunner 가 그 건수로
     * 행을 닫는다. 여기서 행을 닫지 않는다.
     */
    @Override
    public Integer runCounting(LocalDate businessDate, int attemptNo) {
        return retention.purge();
    }
}
