package com.launchcatch.billing.job;

import com.launchcatch.billing.service.PgChargeReconciliationService;
import com.launchcatch.global.logging.FailureLogDetails;
import com.launchcatch.ops.scheduler.StandaloneJob;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 전일 생성 결제를 PG와 대사한다(PG_VS_CHARGE, 구현 순서 13번). 00:05 에 돈다. 00:00 묶음(DailyJob)이 아니라
 * 정산의 독립 스케줄 작업이다. 캠페인 예산 예약 체인과 데이터 의존이 없어서 묶음에 넣지 않는다.
 *
 * 배치 서버 두 대가 같은 시각에 시도하므로 batch_execution_log 점유로 겹치지 않게 한다(배치
 * 운영 문서 2장). KakaoUnlinkRetryScheduler 와 같은 패턴이다.
 *
 * 멈춘 실행은 다른 서버가 이어받아 다시 돌린다. 그 길이 StandaloneJob 이다(#623). 관리자 수동
 * 재실행도 같은 길로 다시 돈다.
 *
 * 같은 영업일로 다시 돌아도 안전하다. PG 조회는 읽기 전용이고 기록은 덮어쓰지 않고 없을 때만
 * 넣는다. 빠진 CHARGE 는 결제당 한 줄(uk_point_ledger_payment_entry)이라 이미 보정한 결제는 다시
 * 보정하지 않고, 불일치 행도 같은 영업일과 결제로는 한 번만 남는다. 그래서 늦게 깨어난 이전 시도가
 * 새 시도의 결과를 덮을 일이 없어 BatchAttemptGuard 로 세대를 확인하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgChargeReconciliationScheduler implements StandaloneJob {

    /** 점유 키다. 영업일은 붙이지 않는다. */
    static final String JOB_NAME = "BILLING_PG_VS_CHARGE_RECON";

    private final PgChargeReconciliationService reconciliationService;
    private final BatchExecutionService executions;

    /*
     * 영업일 경계 작업과 같이 시간대를 값으로 적는다. 서버 기본 시간대로 두면 컨테이너가 UTC 로 뜨는 순간 정오에 돈다.
     * 00:05 에 도는 것은 서비스의 워터마크(5분) 때문이다. 00:00 에 돌면 전일 23:55 이후 결제가 대상에서 빠지고
     * 다음 날 실행도 그 결제를 다시 보지 않는다.
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
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

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    // 이어받은 서버와 관리자 수동 재실행이 부른다. 행은 BatchJobRunner 가 닫으므로 여기서 닫지 않는다.
    @Override
    public void run(LocalDate businessDate, int attemptNo) {
        reconciliationService.reconcilePreviousDay(businessDate, JOB_NAME);
    }
}
