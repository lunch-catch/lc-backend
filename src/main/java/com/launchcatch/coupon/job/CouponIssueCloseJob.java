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
public class CouponIssueCloseJob implements StandaloneJob {

    private final CouponLifecycleService lifecycle;
    private final BatchExecutionService executions;
    private final CouponBatchExecutionService batchExecution;

    @Scheduled(cron = "#{@couponSchedule.closeCron()}", zone = "#{@couponSchedule.zone()}")
    public void closeCoupons() {
        LocalDate businessDate = executions.businessDate();
        if (executions.claim(jobName(), businessDate)) {
            batchExecution.runClaimed(CouponBatchTask.CLOSE, businessDate,
                    attemptNo -> lifecycle.close(businessDate, attemptNo));
        }
    }

    @Override
    public String jobName() {
        return CouponBatchTask.CLOSE.jobName();
    }

    @Override
    public void run(LocalDate businessDate, int attemptNo) {
        runCounting(businessDate, attemptNo);
    }

    @Override
    public Integer runCounting(LocalDate businessDate, int attemptNo) {
        return batchExecution.retry(CouponBatchTask.CLOSE, businessDate, attemptNo,
                () -> lifecycle.close(businessDate, attemptNo));
    }
}
