package com.launchcatch.analytics.batch.service;

import static com.launchcatch.analytics.DailyAggregation.JOB_NAME;

import com.launchcatch.analytics.batch.AggregationOutcome;
import com.launchcatch.analytics.batch.PendingReason;
import com.launchcatch.analytics.batch.repository.PendingDateRepository;
import com.launchcatch.global.logging.FailureLogDetails;
import com.launchcatch.ops.contract.BatchAttemptGuard;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
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
 * 잡은 시도의 실행 세대(attemptNo)를 끝까지 들고 간다. 실행 기록에 쓰는 일(공개와 성공 기록, 실패
 * 기록, 재실행 기록)과 미처리 날짜를 바꾸는 일은 모두 BatchAttemptGuard 로 세대를 확인한 트랜잭션
 * 안에서 한다. 세대가 맞지 않으면 아무것도 쓰지 않고 LEASE_LOST 로 끝낸다(#587). ops 의 소유자 조건만
 * 보면 같은 서버가 새 세대를 잡았을 때 오래된 시도가 그 행을 닫거나 재실행 횟수를 깎는다.
 */
@Slf4j
@Service
public class DailyAggregationRunner {

    /*
     * 정산이 그 영업일의 해제를 끝냈다고 남기는 작업 이름이다. 다음 날 00:00 예약 단계가 남긴다.
     * billing 의 같은 이름 상수와 문자열로만 이어진다. 분석은
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
    private final BatchAttemptGuard guard;
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
            BatchAttemptGuard guard,
            PendingDateRepository pending,
            AggregationMetrics metrics,
            RetryBackoff backoff,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.steps = steps.orderedStream().toList();
        this.executions = executions;
        this.executionQuery = executionQuery;
        this.rerunner = rerunner;
        this.guard = guard;
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
        metrics.resetViolations();
        Map<LocalDate, AggregationOutcome> outcomes = new LinkedHashMap<>();
        for (LocalDate date : targetDates(LocalDate.now(clock))) {
            outcomes.put(date, runGuarded(date));
        }
        metrics.recordPendingDates(pending.count());
        return outcomes;
    }

    /*
     * 오늘 집계할 날짜다. 전일, 전전일, 미처리 날짜, 원천 확정 뒤 집계가 끝나지 않은 날짜를 오래된 날짜부터 돌려준다. 보존 기간 밖이면
     * 빼고 미처리 목록에서도 지운다.
     */
    public List<LocalDate> targetDates(LocalDate today) {
        LocalDate oldestRetained = oldestRetained(today);
        pending.removeBefore(oldestRetained);
        TreeSet<LocalDate> dates = new TreeSet<>(pending.dates());
        dates.addAll(unaggregatedConfirmedDates(oldestRetained, today));
        dates.add(today.minusDays(1));
        dates.add(today.minusDays(2));
        dates.removeIf(date -> date.isBefore(oldestRetained) || !date.isBefore(today));
        return List.copyOf(dates);
    }

    /*
     * 원천 확정은 됐는데 집계가 SUCCESS 로 끝나지 않은 영업일이다. 여러 날 집계가 돌지 못했거나 미처리 기록마저 남기지
     * 못한 날짜가 전일과 전전일 밖에 남아 있어도 여기서 찾는다.
     */
    private List<LocalDate> unaggregatedConfirmedDates(LocalDate oldestRetained, LocalDate today) {
        Set<LocalDate> aggregated = new HashSet<>(executionQuery.findSucceededBusinessDates(JOB_NAME, oldestRetained, today));
        return executionQuery.findSucceededBusinessDates(SOURCE_JOB_NAME, oldestRetained, today).stream()
                .filter(date -> !aggregated.contains(date))
                .toList();
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
        Optional<BatchExecutionRecord> acquired = acquire(date, records);
        if (acquired.isEmpty()) {
            metrics.countResult("busy");
            return AggregationOutcome.BUSY;
        }
        return aggregateDate(date, acquired.get().attemptNo());
    }

    /*
     * 그 날짜를 이 서버 것으로 잡고 잡은 실행 행을 돌려준다. 행이 없으면 claim, FAILED 면 retry,
     * SUCCESS 면 rerun 이고 RUNNING 이면 잡지 못한다. 바뀐 행이 하나일 때만 잡은 것이다.
     *
     * 세 호출은 세대를 돌려주지 않아 잡은 직후 다시 읽는다. 잡은 행은 updated_at 이 지금이라 2분
     * 안에는 이어받기에 걸리지 않으므로 지금 읽은 세대가 이 시도의 세대다.
     */
    public Optional<BatchExecutionRecord> acquire(LocalDate date, List<BatchExecutionRecord> records) {
        Optional<BatchExecutionRecord> own = ownRecord(records);
        boolean acquired = own.isEmpty()
                ? executions.claim(JOB_NAME, date)
                : switch (own.get().status()) {
                    case STATUS_FAILED -> executions.retry(JOB_NAME, date);
                    case STATUS_SUCCESS -> rerunner.rerun(JOB_NAME, date);
                    default -> false;
                };
        if (!acquired) {
            return Optional.empty();
        }
        try {
            return Optional.of(ownRecord(executionQuery.findByBusinessDate(date)).orElseThrow());
        } catch (RuntimeException readFailure) {
            try {
                abandon(date, "잡은 시도의 세대를 읽지 못했다");
            } catch (RuntimeException abandonFailure) {
                readFailure.addSuppressed(abandonFailure);
            }
            throw readFailure;
        }
    }

    /*
     * 수동 재계산과 이어받기(DailyAggregationJob.run)가 날짜 하나만 집계할 때 부른다.
     * 그 날짜 하나가 한 실행이라 정합성 위반 수를 먼저 0 으로 되돌린다.
     */
    public AggregationOutcome aggregate(LocalDate date, int attemptNo) {
        metrics.resetViolations();
        return aggregateDate(date, attemptNo);
    }

    /*
     * 잡은 날짜를 그 시도의 세대로 집계한다. 일시적 오류만 다시 하고 그 밖의 오류는 바로 FAILED 로
     * 닫는다.
     */
    private AggregationOutcome aggregateDate(LocalDate date, int attemptNo) {
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    return attemptOnce(date, attemptNo);
                } catch (AttemptLost lost) {
                    throw lost;
                } catch (RuntimeException failure) {
                    if (!shouldRetry(date, attemptNo, attempt, failure)) {
                        return fail(date, attemptNo, failure);
                    }
                    if (!leaseHeld(date)) {
                        return AggregationOutcome.LEASE_LOST;
                    }
                }
            }
        } catch (AttemptLost lost) {
            log.warn("event=ANALYTICS_DAILY_AGGREGATION_ATTEMPT_LOST businessDate={} attemptNo={}", date, attemptNo);
            metrics.countResult("lease_lost");
            return AggregationOutcome.LEASE_LOST;
        }
    }

    /*
     * 날짜는 잡았는데 집계를 시작하지 못했을 때 그 실행을 실패로 닫고 미처리로 남긴다. 닫지 않으면
     * RUNNING 으로 남아 다른 서버가 이어받아 의미 없이 실패 처리한다.
     */
    public void abandon(LocalDate date, String reason) {
        executions.fail(JOB_NAME, date, reason);
        pending.record(date, PendingReason.FAILED, now());
        metrics.countResult("failed");
    }

    private AggregationOutcome runGuarded(LocalDate date) {
        try {
            return runDate(date);
        } catch (RuntimeException unexpected) {
            FailureLogDetails details = FailureLogDetails.from(unexpected);
            log.error("event=ANALYTICS_DAILY_AGGREGATION_FAILED businessDate={} reason=SYSTEM_ERROR "
                            + "exceptionType={} sqlState={} sqlErrorCode={} origin={}",
                    date, unexpected.getClass().getSimpleName(), details.sqlState(), details.sqlErrorCode(),
                    details.origin());
            pending.record(date, PendingReason.FAILED, now());
            metrics.countResult("failed");
            return AggregationOutcome.FAILED;
        }
    }

    private boolean sourceConfirmed(List<BatchExecutionRecord> records) {
        return records.stream().anyMatch(record ->
                SOURCE_JOB_NAME.equals(record.jobName()) && STATUS_SUCCESS.equals(record.status()));
    }

    private Optional<BatchExecutionRecord> ownRecord(List<BatchExecutionRecord> records) {
        return records.stream()
                .filter(record -> JOB_NAME.equals(record.jobName()))
                .findFirst();
    }

    /*
     * 성공 기록과 미처리 날짜 삭제를 공개와 같은 트랜잭션에 넣는다. succeed 는 거절돼도 알려 주지 않아서
     * 공개만 커밋하고 기록이 빠지는 경우를 따로 가릴 수 없다. 세대 확인이 행을 잠근 채로 셋을 함께
     * 커밋하거나 함께 롤백한다.
     */
    private AggregationOutcome attemptOnce(LocalDate date, int attemptNo) {
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
        inAttempt(date, attemptNo, () -> {
            int rows = publishAll(publications);
            executions.succeed(JOB_NAME, date, rows);
            pending.remove(date);
            return rows;
        });
        metrics.countResult("success");
        return AggregationOutcome.SUCCEEDED;
    }

    /*
     * 세대를 확인하고 이어서 work 를 한 트랜잭션에서 한다. 세대 확인은 실행 행을 잠그므로 커밋할 때까지
     * 다른 서버가 이어받지 못한다.
     *
     * ops 계약은 세대가 맞지 않을 때 던지는 예외의 타입을 적지 않고 구현은 IllegalStateException 을 던진다.
     * 그 예외만 AttemptLost 로 바꾼다. 락 대기 초과 같은 DB 오류는 그대로 올려 기존 재시도와 실패 경로를 탄다.
     */
    private <T> T inAttempt(LocalDate date, int attemptNo, Supplier<T> work) {
        return transactions.execute(status -> {
            try {
                guard.assertCurrentAttempt(JOB_NAME, date, attemptNo);
            } catch (IllegalStateException lost) {
                throw new AttemptLost(lost);
            }
            return work.get();
        });
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
     * 있는가를 모두 만족할 때만 다시 한다. 세대가 맞지 않으면 기록하지 않고 AttemptLost 를 던진다.
     */
    private boolean shouldRetry(LocalDate date, int attemptNo, int attempt, RuntimeException failure) {
        return TransientFailures.isTransient(failure)
                && Boolean.TRUE.equals(inAttempt(date, attemptNo,
                        () -> executions.recordRetry(JOB_NAME, date, describe(failure))))
                && backoff.pause(attempt);
    }

    /*
     * 생존 신호를 갱신한다. 내 행이 아니면 다른 서버가 이어받은 것이라 더 돌지 않는다.
     */
    private boolean leaseHeld(LocalDate date) {
        if (executions.renew(JOB_NAME, date)) {
            return true;
        }
        log.warn("event=ANALYTICS_DAILY_AGGREGATION_LEASE_LOST businessDate={}", date);
        metrics.countResult("lease_lost");
        return false;
    }

    /*
     * 실패 기록과 미처리 기록을 세대를 확인한 트랜잭션에서 한다. 세대가 맞지 않으면 AttemptLost 가 그대로
     * 올라가 아무것도 남기지 않는다. 기록이 다른 이유로 실패하면 미처리만은 따로 남긴다.
     */
    private AggregationOutcome fail(LocalDate date, int attemptNo, RuntimeException failure) {
        FailureLogDetails details = FailureLogDetails.from(failure);
        boolean transientFailure = TransientFailures.isTransient(failure);
        String reason = transientFailure ? "TRANSIENT_EXHAUSTED" : "SYSTEM_ERROR";
        String message = "event=ANALYTICS_DAILY_AGGREGATION_FAILED businessDate={} reason={} exceptionType={} "
                + "sqlState={} sqlErrorCode={} origin={}";
        Object[] fields = {date, reason, failure.getClass().getSimpleName(), details.sqlState(),
                details.sqlErrorCode(), details.origin()};
        if (transientFailure) {
            log.warn(message, fields);
        } else {
            log.error(message, fields);
        }
        try {
            inAttempt(date, attemptNo, () -> {
                executions.fail(JOB_NAME, date, describe(failure));
                pending.record(date, PendingReason.FAILED, now());
                return null;
            });
        } catch (AttemptLost lost) {
            throw lost;
        } catch (RuntimeException recordFailure) {
            failure.addSuppressed(recordFailure);
            pending.record(date, PendingReason.FAILED, now());
        }
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

    // 이 시도의 세대가 더 이상 현재가 아니다. aggregate 가 받아 LEASE_LOST 로 끝낸다.
    private static final class AttemptLost extends RuntimeException {

        AttemptLost(IllegalStateException cause) {
            super(cause);
        }
    }
}
