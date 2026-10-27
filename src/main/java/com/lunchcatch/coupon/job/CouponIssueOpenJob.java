package com.lunchcatch.coupon.job;

import com.lunchcatch.coupon.CouponBatchTask;
import com.lunchcatch.coupon.service.CouponBatchExecutionService;
import com.lunchcatch.coupon.service.CouponLifecycleService;
import com.lunchcatch.ops.scheduler.StandaloneJob;
import com.lunchcatch.ops.service.BatchExecutionService;
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

    @Scheduled(cron = "#{@couponSchedule.openCron()}", zone = "#{@couponSchedule.zone()}")
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
