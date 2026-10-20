package com.launchcatch.ops.service;

import com.launchcatch.ops.scheduler.DailyJob;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.scheduler.StandaloneJob;
import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.scheduler.TransientFailures;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 00:00 묶음과 등록된 묶음 밖 작업(StandaloneJob)을 실제로 돌린다 (배치 운영 문서 2장, 3장).
 *
 * DailyJobScheduler(Entry 계층, job/)가 트리거(스케줄, 이어받기 스캔)를 쥐고, 이 클래스가
 * 실행을 책임진다. 계층 규칙(Service 는 Controller, Entry 에게만 불린다, 설계 문서 1.5절)
 * 때문에 나눴다. 관리자 수동 재실행(BatchExecutionRetryService, #362)은 Service 계층이라
 * Entry 계층인 DailyJobScheduler 를 직접 부를 수 없고, 같은 Service 계층인 이 클래스를
 * DailyJobScheduler 와 함께 쓴다.
 *
 * 작업 내용은 모른다. 각 도메인이 DailyJob 을 구현해 등록하고 순서는 @Order 가 정한다 (규칙 6).
 */
@Slf4j
@Service
public class DailyJobRunner {

    /** 묶음 전체의 점유를 나타내는 작업 이름이다. 단계는 각자 자기 이름으로 행을 남긴다. */
    public static final String BUNDLE = "DAILY_0000";

    private final List<DailyJob> jobs;
    private final Set<String> stepNames;
    /*
     * 생성 시점에 한 번만 만든다(코드래빗 #403). 매 스캔마다 만들면 jobName() 이 겹치는
     * 등록이 있을 때 Collectors.toMap 이 매번 던져 묶음 행을 포함한 모든 이어받기가
     * 막힌다. 여기서 만들면 겹치는 등록은 기동 시점에 바로 드러난다.
     */
    private final Map<String, StandaloneJob> standaloneJobsByName;
    private final BatchExecutionService executions;
    private final RetryBackoff backoff;

    public DailyJobRunner(List<DailyJob> jobs, List<StandaloneJob> standaloneJobs,
                          BatchExecutionService executions, RetryBackoff backoff) {
        this.jobs = jobs;
        this.stepNames = jobs.stream().map(DailyJob::jobName).collect(Collectors.toSet());
        this.standaloneJobsByName = standaloneJobs.stream()
                .collect(Collectors.toMap(StandaloneJob::jobName, job -> job));
        this.executions = executions;
        this.backoff = backoff;
    }

    /** 그 이름이 00:00 묶음의 등록된 단계인지 본다. 단계는 묶음 드라이버로만 다시 돈다(독자 재개 대상이 아니다). */
    public boolean isStep(String jobName) {
        return stepNames.contains(jobName);
    }

    /** 그 이름이 다시 실행할 수 있는 작업인지 본다. 묶음이거나 등록된 StandaloneJob 이어야 한다(#362, 관리자 수동 재실행도 이 경계를 그대로 쓴다). */
    public boolean isRetryable(String jobName) {
        return BUNDLE.equals(jobName) || standaloneJobsByName.containsKey(jobName);
    }

    /*
     * 단계를 차례로 돈다. 앞 단계가 실패하면 뒤 단계는 돌지 않는다 (규칙 6).
     *
     * 단계마다 먼저 묶음 점유를 갱신한다. 0건이면 이미 다른 서버가 이어받은 것이라 멈춘다.
     * 그대로 진행하면 두 서버가 같은 단계를 겹쳐 돈다.
     */
    public void runBundle(LocalDate businessDate) {
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

    /*
     * 이어받기와 관리자 수동 재실행이 함께 쓰는 재개 경로다(#362).
     *
     * 묶음은 runBundle 과 같은 경로로 SUCCESS 단계를 건너뛰고 이어간다. StandaloneJob 은
     * attempt 로 한 번 돌리고 일시적 오류면 자동 재시도한다. isRetryable 이 아닌 이름은
     * 호출자가 먼저 걸러야 한다.
     *
     * StandaloneJob 은 runCounting 으로 돌려 돌려받은 건수로 닫는다(#559). 이 경로는 그 작업의
     * 스케줄러를 거치지 않아 건수를 남길 길이 이것뿐이다.
     */
    public void resume(String jobName, LocalDate businessDate, int attemptNo) {
        if (BUNDLE.equals(jobName)) {
            runBundle(businessDate);
            return;
        }
        StandaloneJob job = standaloneJobsByName.get(jobName);
        attempt(job.jobName(), businessDate, () -> job.runCounting(businessDate, attemptNo), 1, () -> { });
    }

    /*
     * 한 단계를 돈다. 묶음을 계속 진행해도 되는지 돌려준다.
     *
     * BLOCKED는 그 단계 행이 FAILED로 남아 있다는 뜻일 수 있다. 관리자가 묶음(DAILY_0000)을
     * 수동 재실행하면 묶음 행만 RUNNING으로 되돌아가고, 실제로 실패했던 단계 행은 그대로
     * FAILED다(#362). 그 행을 여기서 retry로 한 번 더 되돌려야 그 단계가 다시 실행된다.
     * 되돌리지 못하면(다른 실행이 진행 중이거나 FAILED가 아닌 경우) 그대로 묶음을 실패시킨다.
     */
    private boolean runStep(DailyJob job, LocalDate businessDate) {
        StepOutcome outcome = executions.begin(job.jobName(), businessDate);
        if (outcome == StepOutcome.ALREADY_DONE) {
            log.info("이미 끝난 단계를 건너뛴다. job={} businessDate={}", job.jobName(), businessDate);
            return true;
        }
        if (outcome == StepOutcome.BLOCKED) {
            if (!executions.retry(job.jobName(), businessDate)) {
                executions.fail(BUNDLE, businessDate, job.jobName() + " 단계를 시작할 수 없다");
                return false;
            }
        }
        return attempt(job.jobName(), businessDate, () -> {
            job.run(businessDate);
            return null;
        }, 1, () -> executions.fail(BUNDLE, businessDate, job.jobName() + " 단계가 실패했다"));
    }

    /*
     * 한 번 돌려 보고, 일시적 오류면 기다렸다가 다시 돌린다.
     *
     * 묶음 단계와 묶음 밖 작업이 같은 재실행 규칙을 쓴다(#360). 다른 것은 실패했을 때 묶음까지
     * 실패시킬지뿐이라 onFailure 로 받는다. 다시 도는 것은 이 작업뿐이다. 묶음을 처음부터
     * 돌리면 이미 성공한 단계가 다시 돌아, 멱등이라도 집계가 두 번 더해질 여지가 생긴다.
     *
     * action 은 처리 건수를 돌려준다. null 이면 건수 없이 닫는다(#559, 묶음 단계는 늘 null 이다).
     */
    private boolean attempt(String jobName, LocalDate businessDate, Supplier<Integer> action, int attempt,
                             Runnable onFailure) {
        try {
            Integer processed = action.get();
            if (processed == null) {
                executions.succeed(jobName, businessDate);
            } else {
                executions.succeed(jobName, businessDate, processed);
            }
            return true;
        } catch (RuntimeException e) {
            return retryOrFail(jobName, businessDate, action, attempt, e, onFailure);
        }
    }

    /*
     * 셋 중 하나라도 아니면 실패로 확정한다.
     * 일시적 오류인가, 재실행을 기록할 수 있는가(상한에 닿았거나 내 것이 아니면 0건이다),
     * 기다릴 수 있는가.
     *
     * 내 것이 아니어서 실패로 가는 경우에도 close 가 소유자를 보므로 남의 행을 닫지 않는다.
     */
    private boolean retryOrFail(String jobName, LocalDate businessDate, Supplier<Integer> action, int attempt,
                                 RuntimeException e, Runnable onFailure) {
        String reason = describe(e);
        if (!TransientFailures.isTransient(e)
                || !executions.recordRetry(jobName, businessDate, reason)
                || !backoff.pause(attempt)) {
            executions.fail(jobName, businessDate, reason);
            onFailure.run();
            return false;
        }
        /*
         * 기다린 사이에 임대를 잃었을 수 있다. 다시 돌리기 전에 확인한다.
         * 확인하지 않으면 이어받은 서버와 같은 작업을 겹쳐 돌린다. 작업이 멱등이라도 겹쳐 돌면
         * 집계가 두 번 더해질 여지가 생긴다.
         */
        if (!executions.renew(jobName, businessDate)) {
            log.warn("대기 중에 작업을 다른 서버가 이어받아 중단한다. job={} businessDate={}",
                    jobName, businessDate);
            return false;
        }
        return attempt(jobName, businessDate, action, attempt + 1, onFailure);
    }

    private String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
