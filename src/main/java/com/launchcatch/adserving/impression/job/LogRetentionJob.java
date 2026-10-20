package com.launchcatch.adserving.impression.job;

import com.launchcatch.adserving.impression.service.LogRetentionService;
import com.launchcatch.global.logging.FailureLogDetails;
import com.launchcatch.ops.scheduler.StandaloneJob;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 매일 02:00 에 광고 서빙 테이블의 보관 기간이 지난 행을 지운다(#610).
 * 큰 로그 테이블을 지우는 동안 예산 예약과 후보 적재가 늦어지지 않게 00:00 묶음에 넣지 않는다. 트래픽이 가장 적은 시간대이고 다른 새벽 작업(01:00, 03:00, 04:00)과 겹치지 않는다.
 * 배치 서버 두 대가 같은 시각에 시도하고 batch_execution_log 점유로 한 대만 돈다. 멈추면 다른 서버가 runCounting 으로 이어받는다. 분석의 EventLogRetentionJob 과 같은 틀이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogRetentionJob implements StandaloneJob {

    public static final String JOB_NAME = "ADSERVING_LOG_RETENTION";

    private final LogRetentionService retention;
    private final BatchExecutionService executions;

    /*
     * 시간대를 값으로 적는다.
     * 서버 기본 시간대로 두면 컨테이너가 UTC 로 뜨는 순간 낮에 돈다.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void purgeExpired() {
        LocalDate businessDate = executions.businessDate();
        if (!executions.claim(JOB_NAME, businessDate)) {
            log.info("event=ADSERVING_LOG_RETENTION_SKIPPED reason=ALREADY_CLAIMED businessDate={}", businessDate);
            return;
        }
        int deleted;
        try {
            deleted = retention.purge(businessDate);
        } catch (RuntimeException failure) {
            try {
                executions.fail(JOB_NAME, businessDate, FailureLogDetails.from(failure).toString());
            } catch (RuntimeException recordFailure) {
                failure.addSuppressed(recordFailure);
            }
            // 공통 스케줄러 관측이 실패를 인식하고 마지막 성공 시각을 갱신하지 않게 한다
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
     * 이어받은 서버와 수동 재실행이 부른다.
     * 돌려준 건수로 BatchJobRunner 가 행을 닫는다.
     */
    @Override
    public Integer runCounting(LocalDate businessDate, int attemptNo) {
        return retention.purge(businessDate);
    }
}
