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
