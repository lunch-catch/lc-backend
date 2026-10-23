package com.launchcatch.ops.service;

import com.launchcatch.ops.scheduler.BatchJobRegistry;
import com.launchcatch.ops.scheduler.DailyJob;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.scheduler.StandaloneJob;
import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.scheduler.TransientFailures;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 00:00 묶음을 돌리고, 묶음과 등록된 묶음 밖 작업(StandaloneJob)을 다시 돌린다 (배치 운영 문서 2장, 3장).
 *
 * DailyJobScheduler(Entry 계층, job/)가 트리거(스케줄, 이어받기 스캔)를 쥐고, 이 클래스가
 * 실행을 책임진다. 계층 규칙(Service 는 Controller, Entry 에게만 불린다, 설계 문서 1.5절)
 * 때문에 나눴다. 관리자 수동 재실행(BatchExecutionRetryService, #362)은 Service 계층이라
 * Entry 계층인 DailyJobScheduler 를 직접 부를 수 없고, 같은 Service 계층인 이 클래스를
 * DailyJobScheduler 와 함께 쓴다.
 *
 * 작업 내용은 모른다. 각 도메인이 DailyJob 을 구현해 등록하고 순서는 @Order 가 정한다 (규칙 6).
 * 작업 이름이 묶음, 단계, 묶음 밖 작업 중 무엇인지는 BatchJobRegistry 가 가른다(#585).
 */
@Slf4j
@Service
public class BatchJobRunner {

    private static final String BUNDLE = BatchJobRegistry.BUNDLE;

    private final List<DailyJob> jobs;
    private final BatchJobRegistry registry;
    private final BatchExecutionService executions;
    private final RetryBackoff backoff;

    public BatchJobRunner(List<DailyJob> jobs, BatchJobRegistry registry,
                          BatchExecutionService executions, RetryBackoff backoff) {
        this.jobs = jobs;
        this.registry = registry;
        this.executions = executions;
        this.backoff = backoff;
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
                log.warn("event=BATCH_BUNDLE_STOPPED reason=OWNERSHIP_LOST businessDate={} stoppedJob={}", businessDate, job.jobName());
                return;
            }
            if (!runStep(job, businessDate)) {
                return;
            }
        }
        executions.succeed(BUNDLE, businessDate);
        log.info("event=BATCH_BUNDLE_DONE businessDate={} jobCount={}", businessDate, jobs.size());
    }

    /*
     * 이어받기와 관리자 수동 재실행이 함께 쓰는 재개 경로다(#362).
     *
     * 묶음은 runBundle 과 같은 경로로 SUCCESS 단계를 건너뛰고 이어간다. StandaloneJob 은
     * attempt 로 한 번 돌리고 일시적 오류면 자동 재시도한다. 그 둘이 아닌 이름은
     * BatchJobRegistry.standaloneJob 이 IllegalArgumentException 으로 던진다(#585).
     *
     * StandaloneJob 은 runCounting 으로 돌려 돌려받은 건수로 닫는다(#559). 이 경로는 그 작업의
     * 스케줄러를 거치지 않아 건수를 남길 길이 이것뿐이다.
     *
     * StandaloneJob 의 행은 넘겨받은 세대로만 고친다(#591). 같은 서버가 새 세대를 잡으면 소유자
     * 조건은 통과해서, 낡은 시도가 끝나며 새 시도의 행을 닫거나 재실행 횟수를 깎는다.
     */
    public void resume(String jobName, LocalDate businessDate, int attemptNo) {
        if (BUNDLE.equals(jobName)) {
            runBundle(businessDate);
            return;
        }
        StandaloneJob job = registry.standaloneJob(jobName);
        attempt(attemptLease(job.jobName(), businessDate, attemptNo),
                () -> job.runCounting(businessDate, attemptNo), 1, () -> { });
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
            log.info("event=BATCH_JOB_SKIPPED reason=ALREADY_DONE job={} businessDate={}", job.jobName(), businessDate);
            return true;
        }
        if (outcome == StepOutcome.BLOCKED) {
            if (!executions.retry(job.jobName(), businessDate)) {
                executions.fail(BUNDLE, businessDate, job.jobName() + " 단계를 시작할 수 없다");
                return false;
            }
        }
        return attempt(ownerLease(job.jobName(), businessDate), () -> {
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
    private boolean attempt(Lease lease, Supplier<Integer> action, int attempt, Runnable onFailure) {
        try {
            lease.succeed(action.get());
            return true;
        } catch (RuntimeException e) {
            return retryOrFail(lease, action, attempt, e, onFailure);
        }
    }

    /*
     * 셋 중 하나라도 아니면 실패로 확정한다.
     * 일시적 오류인가, 재실행을 기록할 수 있는가(상한에 닿았거나 내 것이 아니면 0건이다),
     * 기다릴 수 있는가.
     *
     * 내 것이 아니어서 실패로 가는 경우에도 close 가 소유자(와 세대)를 보므로 남의 행을 닫지 않는다.
     */
    private boolean retryOrFail(Lease lease, Supplier<Integer> action, int attempt, RuntimeException e,
                                 Runnable onFailure) {
        String reason = describe(e);
        if (!TransientFailures.isTransient(e)
                || !lease.recordRetry(reason)
                || !backoff.pause(attempt)) {
            lease.fail(reason);
            onFailure.run();
            return false;
        }
        /*
         * 기다린 사이에 임대를 잃었을 수 있다. 다시 돌리기 전에 확인한다.
         * 확인하지 않으면 이어받은 서버와 같은 작업을 겹쳐 돌린다. 작업이 멱등이라도 겹쳐 돌면
         * 집계가 두 번 더해질 여지가 생긴다.
         */
        if (!lease.renew()) {
            log.warn("event=BATCH_JOB_STOPPED reason=TAKEN_OVER_OR_NEW_ATTEMPT job={} businessDate={}",
                    lease.jobName(), lease.businessDate());
            return false;
        }
        return attempt(lease, action, attempt + 1, onFailure);
    }

    private String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /*
     * 한 실행 행을 고치는 방법이다. 묶음 단계는 소유자만 보고 묶음 밖 작업은 세대까지 본다(#591).
     *
     * 세대를 Integer 로 들고 다니면 null 이 섞여 세대 검사가 조용히 빠질 수 있다. 그래서 어느 쪽인지를
     * 만들 때 정하고, 세대를 보는 쪽은 int 로만 받는다.
     */
    private interface Lease {

        String jobName();

        LocalDate businessDate();

        boolean renew();

        boolean recordRetry(String reason);

        void succeed(Integer processedCount);

        void fail(String reason);
    }

    // 00:00 묶음 단계가 쓴다. 단계는 자기 세대를 모른다(begin 이 세대를 돌려주지 않는다).
    private Lease ownerLease(String jobName, LocalDate businessDate) {
        return new Lease() {
            @Override
            public String jobName() {
                return jobName;
            }

            @Override
            public LocalDate businessDate() {
                return businessDate;
            }

            @Override
            public boolean renew() {
                return executions.renew(jobName, businessDate);
            }

            @Override
            public boolean recordRetry(String reason) {
                return executions.recordRetry(jobName, businessDate, reason);
            }

            @Override
            public void succeed(Integer processedCount) {
                if (processedCount == null) {
                    executions.succeed(jobName, businessDate);
                } else {
                    executions.succeed(jobName, businessDate, processedCount);
                }
            }

            @Override
            public void fail(String reason) {
                executions.fail(jobName, businessDate, reason);
            }
        };
    }

    // 이어받기와 수동 재실행으로 도는 묶음 밖 작업이 쓴다. 넘겨받은 세대일 때만 행을 고친다.
    private Lease attemptLease(String jobName, LocalDate businessDate, int attemptNo) {
        return new Lease() {
            @Override
            public String jobName() {
                return jobName;
            }

            @Override
            public LocalDate businessDate() {
                return businessDate;
            }

            @Override
            public boolean renew() {
                return executions.renewAttempt(jobName, businessDate, attemptNo);
            }

            @Override
            public boolean recordRetry(String reason) {
                return executions.recordRetryAttempt(jobName, businessDate, attemptNo, reason);
            }

            @Override
            public void succeed(Integer processedCount) {
                executions.succeedAttempt(jobName, businessDate, attemptNo, processedCount);
            }

            @Override
            public void fail(String reason) {
                executions.failAttempt(jobName, businessDate, attemptNo, reason);
            }
        };
    }
}
