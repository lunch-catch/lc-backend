package com.launchcatch.ops.job;

import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.scheduler.DailyJob;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.scheduler.TransientFailures;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 00:00 묶음을 돌리고, 멈춘 실행을 이어받는다 (배치 운영 문서 2장).
 *
 * 배치 서버 두 대가 모두 이 클래스를 띄우고 같은 시각에 시도한다. 묶음 전체를 행 하나로
 * 점유해서 이긴 서버만 단계를 차례로 돈다. 단계마다 따로 점유하면 앞 단계가 끝나기 전에 다른
 * 서버가 뒤 단계를 시작할 수 있다.
 *
 * 작업 내용은 모른다. 각 도메인이 DailyJob 을 구현해 등록하고 순서는 @Order 가 정한다 (규칙 6).
 */
@Slf4j
@Component
public class DailyJobScheduler {

    /** 묶음 전체의 점유를 나타내는 작업 이름이다. 단계는 각자 자기 이름으로 행을 남긴다. */
    public static final String BUNDLE = "DAILY_0000";

    private final List<DailyJob> jobs;
    private final BatchExecutionService executions;
    private final RetryBackoff backoff;

    public DailyJobScheduler(List<DailyJob> jobs, BatchExecutionService executions,
                             RetryBackoff backoff) {
        this.jobs = jobs;
        this.executions = executions;
        this.backoff = backoff;
    }

    /*
     * 영업일 경계 작업이다. 시간대를 값으로 적는다.
     * 서버 기본 시간대로 두면 컨테이너가 UTC 로 뜨는 순간 오전 9시에 돈다.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void runDailyBundle() {
        LocalDate businessDate = executions.businessDate();
        if (!executions.claim(BUNDLE, businessDate)) {
            log.info("00:00 묶음은 다른 배치 서버가 점유했다. businessDate={}", businessDate);
            return;
        }
        runBundle(businessDate);
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
     * 묶음을 이어받으면 SUCCESS 가 아닌 첫 단계부터 다시 돈다. 묶음의 단계는 따로 손대지
     * 않는다. 묶음을 다시 도는 길에서 begin 이 그 행을 다시 가져간다.
     *
     * 그 둘이 아닌 작업은 이어받아도 다시 돌릴 방법이 없다. 소유자만 바꿔 두면 내 생존 신호가
     * 그 행을 영원히 살아 있게 만들어 아무도 멈춘 것을 모른다. 그래서 실패로 닫고 알린다.
     */
    @Scheduled(fixedDelayString = "${launchcatch.batch.takeover-scan:1m}")
    public void takeOverStale() {
        List<BatchExecutionLog> taken = executions.takeOverStale();
        Set<String> steps = stepNames();
        taken.stream()
                .filter(row -> !BUNDLE.equals(row.getJobName()))
                .filter(row -> !steps.contains(row.getJobName()))
                .forEach(row -> executions.fail(row.getJobName(), row.getBusinessDate(),
                        "멈춘 실행을 이어받았으나 다시 돌릴 경로가 없다"));
        taken.stream()
                .filter(row -> BUNDLE.equals(row.getJobName()))
                .forEach(row -> runBundle(row.getBusinessDate()));
    }

    /*
     * 단계를 차례로 돈다. 앞 단계가 실패하면 뒤 단계는 돌지 않는다 (규칙 6).
     *
     * 단계마다 먼저 묶음 점유를 갱신한다. 0건이면 이미 다른 서버가 이어받은 것이라 멈춘다.
     * 그대로 진행하면 두 서버가 같은 단계를 겹쳐 돈다.
     */
    private void runBundle(LocalDate businessDate) {
        for (DailyJob job : jobs) {
            if (!executions.renew(BUNDLE, businessDate)) {
                log.warn("묶음 점유를 잃어 중단한다. businessDate={} 멈춘단계={}", businessDate, job.jobName());
                return;
            }
            if (!runStep(job, businessDate)) {
                return;
            }
        }
        executions.succeed(BUNDLE, businessDate);
        log.info("00:00 묶음을 마쳤다. businessDate={} 단계={}", businessDate, jobs.size());
    }

    /** 한 단계를 돈다. 묶음을 계속 진행해도 되는지 돌려준다. */
    private boolean runStep(DailyJob job, LocalDate businessDate) {
        StepOutcome outcome = executions.begin(job.jobName(), businessDate);
        if (outcome == StepOutcome.ALREADY_DONE) {
            log.info("이미 끝난 단계를 건너뛴다. job={} businessDate={}", job.jobName(), businessDate);
            return true;
        }
        if (outcome == StepOutcome.BLOCKED) {
            executions.fail(BUNDLE, businessDate, job.jobName() + " 단계를 시작할 수 없다");
            return false;
        }
        return attempt(job, businessDate, 1);
    }

    /*
     * 한 번 돌려 보고, 일시적 오류면 기다렸다가 다시 돌린다.
     *
     * 다시 도는 것은 그 단계뿐이다. 묶음을 처음부터 돌리면 이미 성공한 단계가 다시 돌아,
     * 멱등이라도 집계가 두 번 더해질 여지가 생긴다.
     */
    private boolean attempt(DailyJob job, LocalDate businessDate, int attempt) {
        try {
            job.run(businessDate);
            executions.succeed(job.jobName(), businessDate);
            return true;
        } catch (RuntimeException e) {
            return retryOrFail(job, businessDate, attempt, e);
        }
    }

    /*
     * 셋 중 하나라도 아니면 실패로 확정한다.
     * 일시적 오류인가, 상한이 남았는가(recordRetry 가 0건이면 닿았다), 기다릴 수 있는가.
     */
    private boolean retryOrFail(DailyJob job, LocalDate businessDate, int attempt, RuntimeException e) {
        String reason = describe(e);
        if (!TransientFailures.isTransient(e)
                || !executions.recordRetry(job.jobName(), businessDate, reason)
                || !backoff.pause(attempt)) {
            executions.fail(job.jobName(), businessDate, reason);
            executions.fail(BUNDLE, businessDate, job.jobName() + " 단계가 실패했다");
            return false;
        }
        return attempt(job, businessDate, attempt + 1);
    }

    private Set<String> stepNames() {
        return jobs.stream().map(DailyJob::jobName).collect(Collectors.toSet());
    }

    private String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
