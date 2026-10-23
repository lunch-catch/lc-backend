package com.launchcatch.coupon.job;

import com.launchcatch.coupon.CouponBatchTask;
import com.launchcatch.coupon.service.CouponBatchExecutionService;
import com.launchcatch.coupon.service.CouponLifecycleService;
import com.launchcatch.ops.scheduler.StandaloneJob;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CouponIssueOpenJob implements StandaloneJob {

    private final CouponLifecycleService lifecycle;
    private final BatchExecutionService executions;
    private final CouponBatchExecutionService batchExecution;

    @Scheduled(cron = "0 0 11 * * *", zone = "Asia/Seoul")
    public void openCoupons() {
        LocalDate businessDate = executions.businessDate();
        if (executions.claim(jobName(), businessDate)) {
            batchExecution.runClaimed(CouponBatchTask.OPEN, businessDate,
                    attemptNo -> lifecycle.open(businessDate, attemptNo));
        }
    }

    @Override
    public String jobName() {
        return CouponBatchTask.OPEN.jobName();
    }

    @Override
    public void run(LocalDate businessDate, int attemptNo) {
        runCounting(businessDate, attemptNo);
    }

    @Override
    public Integer runCounting(LocalDate businessDate, int attemptNo) {
        return batchExecution.retry(CouponBatchTask.OPEN, businessDate, attemptNo,
                () -> lifecycle.open(businessDate, attemptNo));
    }
}
