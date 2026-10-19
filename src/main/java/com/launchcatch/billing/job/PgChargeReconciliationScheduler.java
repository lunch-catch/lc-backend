package com.launchcatch.billing.job;

import com.launchcatch.billing.service.PgChargeReconciliationService;
import com.launchcatch.global.logging.FailureLogDetails;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 전일 생성 결제를 PG와 대사한다(PG_VS_CHARGE, 구현 순서 13번). 00:00 묶음(DailyJob)이 아니라
 * 정산의 독립 스케줄 작업이다. 캠페인 예산 예약 체인과 데이터 의존이 없어서 묶음에 넣지 않는다.
 *
 * 배치 서버 두 대가 같은 시각에 시도하므로 batch_execution_log 점유로 겹치지 않게 한다(배치
 * 운영 문서 2장). KakaoUnlinkRetryScheduler 와 같은 패턴이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgChargeReconciliationScheduler {

    /** 점유 키다. 영업일은 붙이지 않는다. */
    static final String JOB_NAME = "BILLING_PG_VS_CHARGE_RECON";

    private final PgChargeReconciliationService reconciliationService;
    private final BatchExecutionService executions;

    // 영업일 경계 작업과 같이 시간대를 값으로 적는다. 서버 기본 시간대로 두면 컨테이너가 UTC 로 뜨는 순간 정오에 돈다
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void reconcile() {
        LocalDate businessDate = executions.businessDate();
        if (!executions.claim(JOB_NAME, businessDate)) {
            log.info("event=BILLING_PG_RECON_SKIPPED reason=ALREADY_CLAIMED businessDate={}", businessDate);
            return;
        }
        try {
            reconciliationService.reconcilePreviousDay(businessDate, JOB_NAME);
        } catch (RuntimeException e) {
            try {
                executions.fail(JOB_NAME, businessDate, FailureLogDetails.from(e).toString());
            } catch (RuntimeException recordFailure) {
                e.addSuppressed(recordFailure);
            }
            throw e;
        }
        executions.succeed(JOB_NAME, businessDate);
    }
}
