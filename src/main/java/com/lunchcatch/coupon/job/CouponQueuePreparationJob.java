package com.lunchcatch.coupon.job;

import com.lunchcatch.coupon.CouponBatchTask;
import com.lunchcatch.coupon.service.CouponBatchExecutionService;
import com.lunchcatch.coupon.service.CouponQueuePreparationService;
import com.lunchcatch.ops.scheduler.StandaloneJob;
import com.lunchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CouponQueuePreparationJob implements StandaloneJob {

    private final CouponQueuePreparationService preparation;
    private final BatchExecutionService executions;
    private final CouponBatchExecutionService batchExecution;

    // 발급 오픈에 첫 요청이 큐 복구를 기다리지 않도록 설정된 여유시간 전에 오늘 회차의 Redis 큐를 만든다.
    @Scheduled(cron = "#{@couponSchedule.queuePreparationCron()}", zone = "#{@couponSchedule.zone()}")
    public void prepareQueues() {
        LocalDate businessDate = executions.businessDate();
        if (executions.claim(jobName(), businessDate)) {
            batchExecution.runClaimed(CouponBatchTask.QUEUE_PREPARE, businessDate,
                    attemptNo -> preparation.prepare(businessDate, attemptNo));
        }
    }

    @Override
    public String jobName() {
        return CouponBatchTask.QUEUE_PREPARE.jobName();
    }

    @Override
    public void run(LocalDate businessDate, int attemptNo) {
        runCounting(businessDate, attemptNo);
    }

    @Override
    public Integer runCounting(LocalDate businessDate, int attemptNo) {
        return batchExecution.retry(CouponBatchTask.QUEUE_PREPARE, businessDate, attemptNo,
                () -> preparation.prepare(businessDate, attemptNo));
    }
}
