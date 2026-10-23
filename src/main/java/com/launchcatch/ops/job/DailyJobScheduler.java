package com.launchcatch.ops.job;

import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.scheduler.BatchJobRegistry;
import com.launchcatch.ops.scheduler.BatchJobRegistry.Kind;
import com.launchcatch.ops.service.BatchExecutionService;
import com.launchcatch.ops.service.BatchJobRunner;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 00:00 묶음을 돌리고, 멈춘 실행을 이어받는 트리거다 (배치 운영 문서 2장).
 *
 * 배치 서버 두 대가 모두 이 클래스를 띄우고 같은 시각에 시도한다. 묶음 전체를 행 하나로
 * 점유해서 이긴 서버만 단계를 차례로 돈다. 단계마다 따로 점유하면 앞 단계가 끝나기 전에 다른
 * 서버가 뒤 단계를 시작할 수 있다.
 *
 * 실제로 묶음을 돌리고 재개하는 로직은 BatchJobRunner(Service 계층)가 갖는다. 이 클래스는
 * Entry 계층이라 Service 는 이 클래스를 부를 수 없다(설계 문서 1.5절). 관리자 수동 재실행
 * (BatchExecutionRetryService, #362)이 그 규칙 때문에 BatchJobRunner 를 직접 쓴다.
 */
@Slf4j
@Component
public class DailyJobScheduler {

    private final BatchJobRunner runner;
    private final BatchJobRegistry registry;
    private final BatchExecutionService executions;

    public DailyJobScheduler(BatchJobRunner runner, BatchJobRegistry registry, BatchExecutionService executions) {
        this.runner = runner;
        this.registry = registry;
        this.executions = executions;
    }

    /*
     * 영업일 경계 작업이다. 시간대를 값으로 적는다.
     * 서버 기본 시간대로 두면 컨테이너가 UTC 로 뜨는 순간 오전 9시에 돈다.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void runDailyBundle() {
        LocalDate businessDate = executions.businessDate();
        if (!executions.claim(BatchJobRegistry.BUNDLE, businessDate)) {
            log.info("event=BATCH_BUNDLE_SKIPPED reason=OWNED_BY_OTHER_SERVER businessDate={}", businessDate);
            return;
        }
        runner.runBundle(businessDate);
    }

    /*
     * 생존 신호다. 이 메서드가 묶음과 같은 스레드를 쓰면 묶음이 도는 동안 한 번도 못 돈다.
     * 그러면 멀쩡히 일하는 서버의 행이 2분 뒤 멈춘 것으로 보여 다른 서버가 빼앗는다.
     * 그래서 application-batch.yml 이 스케줄러 스레드 풀을 1보다 크게 둔다.
     */
    @Scheduled(fixedDelayString = "${launchcatch.batch.heartbeat:30s}")
    public void sendHeartbeat() {
        executions.renewOwned();
    }

    /*
     * 멈춘 실행을 이어받는다.
     *
     * 묶음의 단계는 따로 손대지 않는다(Kind.STEP). 묶음을 다시 도는 길에서 begin 이 그
     * 행을 다시 가져간다. 묶음이거나 등록된 묶음 밖 작업(StandaloneJob)이면 runner.resume 이
     * 다시 돈다(#360). 그 둘도 아니면 다시 돌릴 방법이 없다. 소유자만 바꿔 두면 내 생존
     * 신호가 그 행을 영원히 살아 있게 만들어 아무도 멈춘 것을 모른다. 그래서 실패로 닫고 알린다.
     */
    @Scheduled(fixedDelayString = "${launchcatch.batch.takeover-scan:1m}")
    public void takeOverStale() {
        List<BatchExecutionLog> taken = executions.takeOverStale();
        for (BatchExecutionLog row : taken) {
            Kind kind = registry.classify(row.getJobName());
            if (kind == Kind.STEP) {
                continue;
            } else if (kind.isRetryable()) {
                runner.resume(row.getJobName(), row.getBusinessDate(), row.getAttemptNo());
            } else {
                executions.fail(row.getJobName(), row.getBusinessDate(),
                        "멈춘 실행을 이어받았으나 다시 돌릴 경로가 없다");
            }
        }
    }
}
