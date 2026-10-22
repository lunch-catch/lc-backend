package com.launchcatch.coupon.job;

import com.launchcatch.coupon.CouponBatchTask;
import com.launchcatch.coupon.service.CouponBatchExecutionService;
import com.launchcatch.coupon.service.CouponExpirationService;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import com.launchcatch.ops.scheduler.StandaloneJob;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CouponExpirationJob implements StandaloneJob {

    private final CouponExpirationService expiration;
    private final BatchExecutionService executions;
    private final BatchExecutionQueryService executionQuery;
    private final CouponBatchExecutionService batchExecution;

    /*
     * 매시 0·5·10분처럼 5분 간격으로 사용 시간이 끝난 ISSUED 회원 쿠폰의 영업일을 찾는다.
     * 과거 날짜도 조회하므로 서버 중단과 늦은 발급 커밋으로 남은 쿠폰을 다음 실행에서 처리한다.
     * 대상이 없으면 실행 이력을 만들거나 이미 성공한 실행을 다시 점유하지 않는다.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void expireCoupons() {
        for (LocalDate businessDate : expiration.expiringDates()) {
            expireDue(businessDate);
        }
    }

    /*
     * 실행 이력은 작업명과 영업일로 유지하며 만료 대상이 남으면 이전 SUCCESS나 FAILED를 다시 점유한다.
     * 다음 주기의 재점유는 재시도 횟수를 초기화하고 새 실행 세대로 남은 쿠폰을 처리한다.
     * RUNNING은 공통 이어받기에 맡긴다.
     */
    private void expireDue(LocalDate businessDate) {
        Optional<BatchExecutionRecord> previous = executionQuery.findByBusinessDate(businessDate).stream()
                .filter(row -> jobName().equals(row.jobName())).findFirst();
        boolean acquired = previous.isEmpty() ? executions.claim(jobName(), businessDate)
                : ("SUCCESS".equals(previous.get().status()) || "FAILED".equals(previous.get().status()))
                        && executions.rerun(jobName(), businessDate);
        if (acquired) {
            batchExecution.runClaimed(CouponBatchTask.EXPIRE, businessDate,
                    attemptNo -> expiration.expire(businessDate, attemptNo));
        }
    }

    @Override
    public String jobName() {
        return CouponBatchTask.EXPIRE.jobName();
    }

    @Override
    public void run(LocalDate businessDate, int attemptNo) {
        runCounting(businessDate, attemptNo);
    }

    @Override
    public Integer runCounting(LocalDate businessDate, int attemptNo) {
        return batchExecution.retry(CouponBatchTask.EXPIRE, businessDate, attemptNo,
                () -> expiration.expire(businessDate, attemptNo));
    }
}
