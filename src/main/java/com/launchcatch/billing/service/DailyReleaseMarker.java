package com.launchcatch.billing.service;

import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/*
 * 한 영업일의 해제가 끝났다는 표시(BILLING_DAILY_RELEASE)를 SUCCESS 로 남긴다. 분석 01:00 집계가 이 행으로 그 영업일
 * 원천이 확정됐는지 본다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReleaseMarker {

    /** 해제가 끝났다는 표시의 작업 이름이다. 분석이 같은 문자열로 읽으므로 바꾸지 않는다. */
    static final String RELEASE_JOB_NAME = "BILLING_DAILY_RELEASE";

    private final BatchExecutionService executions;

    // 이미 SUCCESS 면 그대로 둔다. FAILED 는 해제가 끝난 뒤 표시만 남기지 못한 경우일 수 있어 다시 열어 닫는다
    public void mark(LocalDate releasedDate) {
        StepOutcome outcome = executions.begin(RELEASE_JOB_NAME, releasedDate);
        if (outcome == StepOutcome.BLOCKED && executions.retry(RELEASE_JOB_NAME, releasedDate)) {
            outcome = executions.begin(RELEASE_JOB_NAME, releasedDate);
        }
        if (outcome == StepOutcome.STARTED) {
            executions.succeed(RELEASE_JOB_NAME, releasedDate);
        } else if (outcome == StepOutcome.BLOCKED) {
            log.warn("event=BILLING_RELEASE_MARK_BLOCKED businessDate={}", releasedDate);
        }
    }
}
