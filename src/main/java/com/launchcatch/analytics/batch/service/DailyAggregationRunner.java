package com.launchcatch.analytics.batch.service;

import com.launchcatch.analytics.batch.AggregationOutcome;
import com.launchcatch.analytics.batch.PendingReason;
import com.launchcatch.analytics.batch.repository.PendingDateRepository;
import com.launchcatch.global.logging.FailureLogDetails;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import com.launchcatch.ops.contract.BatchRerunner;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.scheduler.TransientFailures;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 일별 집계의 실행기다. 날짜 목록을 만들고, 날짜마다 원천 확정을 확인하고, 실행 행으로 잡고, 모으기와
 * 공개를 나눠 돈다.
 *
 * 하루 결과는 모으기(트랜잭션 없음)와 공개(한 트랜잭션)로 나눈다. 일시적 오류는 공개 트랜잭션을
 * 롤백하고 트랜잭션 밖에서 기다린 뒤 모으기부터 다시 한다. 대기하는 동안 커넥션을 쥐지 않는다.
 *
 * 날짜를 잡는 일은 batch_execution_log 의 (DAILY_AGGREGATION, 날짜) 행으로 한다. 정기 실행과 수동
 * 재계산이 같은 절차를 써서 같은 날짜를 겹쳐 돌지 않는다.
 *
 * 이 실행기는 실행 세대(BatchAttemptGuard)를 확인하지 않는다. 정상 경로에서 자기 세대를 알 방법이
 * 아직 없다(ops 의 BatchExecutionRecord 에 세대가 없다). 늦게 깨어난 서버가 결과를 덮는 경우를
 * 막지 못하는 한계다. 이슈 #423 의 제외 범위이고 후속 작업에서 붙인다.
 */
@Slf4j
@Service
public class DailyAggregationRunner {

    /** batch_execution_log.job_name 이다. 영업일은 붙이지 않는다. */
    public static final String JOB_NAME = "DAILY_AGGREGATION";

    /*
     * 정산 13:30 해제·대사의 작업 이름이다. billing 의 같은 이름 상수와 문자열로만 이어진다. 분석은
     * 정산 내부 타입을 참조하지 않는다.
     */
    static final String SOURCE_JOB_NAME = "BILLING_DAILY_RELEASE";

    /** 보존 기간이다. 이보다 오래된 영업일은 원본 로그가 지워져 다시 집계하면 0 으로 덮인다. */
    public static final int RETENTION_MONTHS = 13;

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private final List<AggregationStep<?>> steps;
    private final BatchExecutionService executions;
    private final BatchExecutionQueryService executionQuery;
    private final BatchRerunner rerunner;
    private final PendingDateRepository pending;
    private final AggregationMetrics metrics;
    private final RetryBackoff backoff;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DailyAggregationRunner(
            ObjectProvider<AggregationStep<?>> steps,
            BatchExecutionService executions,
            BatchExecutionQueryService executionQuery,
            BatchRerunner rerunner,
            PendingDateRepository pending,
            AggregationMetrics metrics,
            RetryBackoff backoff,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.steps = steps.orderedStream().toList();
        this.executions = executions;
        this.executionQuery = executionQuery;
        this.rerunner = rerunner;
        this.pending = pending;
        this.metrics = metrics;
        this.backoff = backoff;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /*
     * 01:00 작업이 부른다. 날짜마다 따로 돌고 한 날짜의 실패가 다른 날짜를 멈추지 않는다.
     */
    public Map<LocalDate, AggregationOutcome> runAll() {
        Map<LocalDate, AggregationOutcome> outcomes = new LinkedHashMap<>();
        for (LocalDate date : targetDates(LocalDate.now(clock))) {
            outcomes.put(date, runGuarded(date));
        }
        metrics.recordPendingDates(pending.count());
        return outcomes;
    }

    /*
     * 오늘 집계할 날짜다. 전일, 전전일, 미처리 날짜를 오래된 날짜부터 돌려준다. 보존 기간 밖이면
     * 빼고 미처리 목록에서도 지운다.
     */
    public List<LocalDate> targetDates(LocalDate today) {
        LocalDate oldestRetained = oldestRetained(today);
        pending.removeBefore(oldestRetained);
        TreeSet<LocalDate> dates = new TreeSet<>(pending.dates());
        dates.add(today.minusDays(1));
        dates.add(today.minusDays(2));
        dates.removeIf(date -> date.isBefore(oldestRetained) || !date.isBefore(today));
        return List.copyOf(dates);
    }

    // 집계를 다시 돌릴 수 있는 가장 오래된 영업일이다.
    public LocalDate oldestRetained(LocalDate today) {
        return today.minusMonths(RETENTION_MONTHS);
    }

    // 한 날짜를 원천 확정 확인, 날짜 잡기, 집계 순서로 처음부터 끝까지 돈다.
    public AggregationOutcome runDate(LocalDate date) {
        List<BatchExecutionRecord> records = executionQuery.findByBusinessDate(date);
        if (!sourceConfirmed(records)) {
            pending.record(date, PendingReason.SOURCE_NOT_READY, now());
            metrics.countResult("source_not_ready");
            return AggregationOutcome.SOURCE_NOT_READY;
        }
        if (!acquire(date, records)) {
            metrics.countResult("busy");
            return AggregationOutcome.BUSY;
        }
        return aggregate(date);
    }

    /*
     * 그 날짜를 이 서버 것으로 잡는다. 행이 없으면 claim, FAILED 면 retry, SUCCESS 면 rerun 이고
     * RUNNING 이면 잡지 못한다. 바뀐 행이 하나일 때만 잡은 것이다.
     */
    public boolean acquire(LocalDate date, List<BatchExecutionRecord> records) {
        Optional<BatchExecutionRecord> own = records.stream()
                .filter(record -> JOB_NAME.equals(record.jobName()))
                .findFirst();
        if (own.isEmpty()) {
            return executions.claim(JOB_NAME, date);
        }
        return switch (own.get().status()) {
            case STATUS_FAILED -> executions.retry(JOB_NAME, date);
            case STATUS_SUCCESS -> rerunner.rerun(JOB_NAME, date);
            default -> false;
        };
    }

    /*
     * 잡은 날짜를 집계한다. 일시적 오류만 다시 하고 그 밖의 오류는 바로 FAILED 로 닫는다.
     * 수동 재계산도 날짜를 잡은 뒤 이 메서드를 부른다.
     */
    public AggregationOutcome aggregate(LocalDate date) {
        for (int attempt = 1; ; attempt++) {
            try {
                return attemptOnce(date);
            } catch (RuntimeException failure) {
                if (!shouldRetry(date, attempt, failure)) {
                    return fail(date, failure);
                }
                if (!leaseHeld(date)) {
                    return AggregationOutcome.LEASE_LOST;
                }
            }
        }
    }

    private AggregationOutcome runGuarded(LocalDate date) {
        try {
            return runDate(date);
        } catch (RuntimeException unexpected) {
            log.error("event=DAILY_AGGREGATION_ERROR businessDate={} cause={}",
                    date, FailureLogDetails.from(unexpected));
            pending.record(date, PendingReason.FAILED, now());
            metrics.countResult("failed");
            return AggregationOutcome.FAILED;
        }
    }

    private boolean sourceConfirmed(List<BatchExecutionRecord> records) {
        return records.stream().anyMatch(record ->
                SOURCE_JOB_NAME.equals(record.jobName()) && STATUS_SUCCESS.equals(record.status()));
    }

    private AggregationOutcome attemptOnce(LocalDate date) {
        List<Publication> publications = new ArrayList<>();
        for (AggregationStep<?> step : steps) {
            if (!leaseHeld(date)) {
                return AggregationOutcome.LEASE_LOST;
            }
            publications.add(collect(step, date));
        }
        if (!leaseHeld(date)) {
            return AggregationOutcome.LEASE_LOST;
        }
        int rows = Objects.requireNonNullElse(
                transactions.execute(status -> publishAll(publications)), 0);
        executions.succeed(JOB_NAME, date, rows);
        pending.remove(date);
        metrics.countResult("success");
        return AggregationOutcome.SUCCEEDED;
    }

    private <R> Publication collect(AggregationStep<R> step, LocalDate date) {
        long started = System.nanoTime();
        R collected = step.collect(date);
        metrics.recordStep(step.name(), Duration.ofNanos(System.nanoTime() - started));
        return () -> {
            int rows = step.publish(date, collected);
            metrics.recordRows(step.name(), rows);
            return rows;
        };
    }

    private int publishAll(List<Publication> publications) {
        int rows = 0;
        for (Publication publication : publications) {
            rows += publication.publish();
        }
        return rows;
    }

    /*
     * 일시적 오류인가, 재실행을 기록할 수 있는가(상한에 닿았거나 내 것이 아니면 거절된다), 기다릴 수
     * 있는가를 모두 만족할 때만 다시 한다.
     */
    private boolean shouldRetry(LocalDate date, int attempt, RuntimeException failure) {
        return TransientFailures.isTransient(failure)
                && executions.recordRetry(JOB_NAME, date, describe(failure))
                && backoff.pause(attempt);
    }

    /*
     * 생존 신호를 갱신한다. 내 행이 아니면 다른 서버가 이어받은 것이라 더 돌지 않는다.
     */
    private boolean leaseHeld(LocalDate date) {
        if (executions.renew(JOB_NAME, date)) {
            return true;
        }
        log.warn("event=DAILY_AGGREGATION_LEASE_LOST businessDate={}", date);
        metrics.countResult("lease_lost");
        return false;
    }

    private AggregationOutcome fail(LocalDate date, RuntimeException failure) {
        log.error("event=DAILY_AGGREGATION_FAILED businessDate={} cause={}",
                date, FailureLogDetails.from(failure));
        try {
            executions.fail(JOB_NAME, date, describe(failure));
        } catch (RuntimeException recordFailure) {
            failure.addSuppressed(recordFailure);
        }
        pending.record(date, PendingReason.FAILED, now());
        metrics.countResult("failed");
        return AggregationOutcome.FAILED;
    }

    private String describe(RuntimeException failure) {
        return FailureLogDetails.from(failure).toString();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private interface Publication {

        int publish();
    }
}
