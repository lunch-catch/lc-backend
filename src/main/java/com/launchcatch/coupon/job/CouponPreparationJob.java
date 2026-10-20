package com.launchcatch.coupon.job;

import com.launchcatch.coupon.CouponBatchTask;
import com.launchcatch.coupon.service.CouponBatchExecutionService;
import com.launchcatch.coupon.service.CouponPreparationService;
import com.launchcatch.ops.scheduler.StandaloneJob;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CouponPreparationJob implements StandaloneJob {

    private final CouponPreparationService preparation;
    private final BatchExecutionService executions;
    private final CouponBatchExecutionService batchExecution;

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void prepareCoupons() {
        LocalDate businessDate = executions.businessDate();
        if (executions.claim(jobName(), businessDate)) {
            batchExecution.runClaimed(CouponBatchTask.PREPARE, businessDate,
                    attemptNo -> preparation.prepare(businessDate, attemptNo));
        }
    }

    @Override
    public String jobName() {
        return CouponBatchTask.PREPARE.jobName();
    }

    @Override
    public void run(LocalDate businessDate, int attemptNo) {
        runCounting(businessDate, attemptNo);
    }

    @Override
    public Integer runCounting(LocalDate businessDate, int attemptNo) {
        return batchExecution.retry(CouponBatchTask.PREPARE, businessDate, attemptNo,
                () -> preparation.prepare(businessDate, attemptNo));
    }
}
