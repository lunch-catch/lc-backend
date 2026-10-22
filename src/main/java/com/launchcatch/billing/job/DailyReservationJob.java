package com.launchcatch.billing.job;

import com.launchcatch.billing.service.DailyReservationService;
import com.launchcatch.ops.scheduler.DailyJob;
import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 00:00 묶음의 예약 단계다(V52 102행 (5)). 순서 값은 102행 단계 번호 × 10 이다.
 *
 * 예약은 전날 남은 예약을 먼저 RELEASE 한다. 서빙이 하루 종일이라 이 해제가 전날의 정식 해제다.
 * 예약까지 끝나면 (BILLING_DAILY_RELEASE, 전날) 을 SUCCESS 로 남긴다. 분석 01:00 집계가 이 행으로 전날 원천이 확정됐는지
 * 본다. 해제는 끝났지만 예약이 실패한 날은 표시가 늦어지고, 이 단계가 다시 돌아 성공할 때 남는다.
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class DailyReservationJob implements DailyJob {

    static final String JOB_NAME = "BILLING_RESERVE";

    /** 전날 해제가 끝났다는 표시의 작업 이름이다. 분석이 같은 문자열로 읽으므로 바꾸지 않는다. */
    static final String RELEASE_JOB_NAME = "BILLING_DAILY_RELEASE";

    private final DailyReservationService reservation;
    private final BatchExecutionService executions;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate) {
        reservation.reserve(businessDate);
        markReleased(businessDate.minusDays(1));
    }

    // 이미 SUCCESS 면 그대로 둔다. 다른 서버가 쥐고 있거나 FAILED 로 남은 행은 건드리지 않고 경고만 남긴다
    private void markReleased(LocalDate releasedDate) {
        StepOutcome outcome = executions.begin(RELEASE_JOB_NAME, releasedDate);
        if (outcome == StepOutcome.STARTED) {
            executions.succeed(RELEASE_JOB_NAME, releasedDate);
        } else if (outcome == StepOutcome.BLOCKED) {
            log.warn("event=BILLING_RELEASE_MARK_BLOCKED businessDate={}", releasedDate);
        }
    }
}
