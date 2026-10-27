package com.lunchcatch.coupon.job;

import com.lunchcatch.coupon.CouponBatchTask;
import com.lunchcatch.coupon.service.CouponBatchExecutionService;
import com.lunchcatch.coupon.service.CouponConsistencyService;
import com.lunchcatch.ops.scheduler.StandaloneJob;
import com.lunchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CouponConsistencyJob implements StandaloneJob {

    private final CouponConsistencyService consistency;
    private final BatchExecutionService executions;
    private final CouponBatchExecutionService batchExecution;

    @Scheduled(cron = "${coupon.batch.consistency-cron}", zone = "#{@couponSchedule.zone()}")
    public void verifyCoupons() {
        LocalDate businessDate = executions.businessDate().minusDays(1);
        if (executions.claim(jobName(), businessDate)) {
            batchExecution.runClaimed(CouponBatchTask.VERIFY, businessDate,
                    attemptNo -> consistency.verify(businessDate, attemptNo));
        }
    }

    @Override
    public String jobName() {
        return CouponBatchTask.VERIFY.jobName();
    }

    @Override
    public void run(LocalDate businessDate, int attemptNo) {
        runCounting(businessDate, attemptNo);
    }

    @Override
    public Integer runCounting(LocalDate businessDate, int attemptNo) {
        return batchExecution.retry(CouponBatchTask.VERIFY, businessDate, attemptNo,
                () -> consistency.verify(businessDate, attemptNo));
    }
}
