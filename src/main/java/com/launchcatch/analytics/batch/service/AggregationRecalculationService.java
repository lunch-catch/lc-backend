package com.launchcatch.analytics.batch.service;

import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import com.launchcatch.ops.contract.AuditLogWriter;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

/*
 * A-05 수동 재계산이다. 날짜를 잡는 데까지만 요청 안에서 하고 집계는 전용 실행기에서 돌려 202 를 준다.
 *
 * 정기 실행과 같은 절차로 날짜를 잡아서(DailyAggregationRunner.acquire) 같은 날짜를 겹쳐 돌지 않는다.
 * 실행 중이면 409 다. 이미 FINAL 인 날짜도 다시 돌릴 수 있고, 공개 트랜잭션이 그날 행을 한 번에
 * 교체해서 도는 동안에도 조회 결과가 비지 않는다.
 *
 * 실행기를 스프링 빈으로 노출하지 않는다. Executor 빈이 하나라도 있으면 부트가 기본 작업 실행기를
 * 만들지 않아, 뒤에 붙을 이벤트 수신(@Async)이 이 단일 스레드 실행기를 쓰게 된다.
 *
 * 한 번에 하나만 돌고 대기열은 4개다. 가득 차면 거절하고, 이미 잡은 실행 행은 실패로 닫아 미처리로
 * 남긴다. 닫지 않으면 RUNNING 으로 남아 2분 뒤 다른 서버가 이어받아 의미 없이 실패 처리한다.
 */
@Service
public class AggregationRecalculationService {

    private static final String AUDIT_ACTION = "ANALYTICS_RECALCULATE";
    private static final int QUEUE_CAPACITY = 4;

    private final DailyAggregationRunner runner;
    private final BatchExecutionQueryService executionQuery;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final Executor executor;
    private final ThreadPoolExecutor ownedExecutor;

    @Autowired
    public AggregationRecalculationService(
            DailyAggregationRunner runner,
            BatchExecutionQueryService executionQuery,
            AuditLogWriter auditLogWriter,
            Clock clock) {
        this.runner = runner;
        this.executionQuery = executionQuery;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
        this.ownedExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new CustomizableThreadFactory("analytics-aggregation-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor = ownedExecutor;
    }

    // 테스트가 집계를 호출 스레드에서 바로 돌리려고 쓴다.
    AggregationRecalculationService(
            DailyAggregationRunner runner,
            BatchExecutionQueryService executionQuery,
            AuditLogWriter auditLogWriter,
            Clock clock,
            Executor executor) {
        this.runner = runner;
        this.executionQuery = executionQuery;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
        this.ownedExecutor = null;
        this.executor = executor;
    }

    /*
     * 날짜를 잡고 집계를 시작시킨다. 실행 행 ID 를 돌려준다.
     *
     * 오늘과 미래, 보존 기간 밖 날짜는 422, 이미 실행 중이면 409 다.
     */
    public long request(Long adminId, LocalDate businessDate) {
        LocalDate today = LocalDate.now(clock);
        if (businessDate == null
                || !businessDate.isBefore(today)
                || businessDate.isBefore(runner.oldestRetained(today))) {
            throw new AnalyticsException(AnalyticsErrorCode.UNAGGREGATABLE_DATE);
        }
        List<BatchExecutionRecord> records = executionQuery.findByBusinessDate(businessDate);
        if (!runner.acquire(businessDate, records)) {
            throw new AnalyticsException(AnalyticsErrorCode.AGGREGATION_ALREADY_RUNNING);
        }
        long runId = runIdOf(businessDate);
        auditLogWriter.write(adminId, AUDIT_ACTION, businessDate.toString(), DailyAggregationRunner.JOB_NAME);
        start(businessDate);
        return runId;
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    private void start(LocalDate businessDate) {
        try {
            executor.execute(() -> runner.aggregate(businessDate));
        } catch (RuntimeException rejected) {
            runner.abandon(businessDate, "수동 재계산을 시작하지 못했다: 집계 실행기가 가득 찼다");
            throw rejected;
        }
    }

    private long runIdOf(LocalDate businessDate) {
        return executionQuery.findByBusinessDate(businessDate).stream()
                .filter(record -> DailyAggregationRunner.JOB_NAME.equals(record.jobName()))
                .map(BatchExecutionRecord::runId)
                .findFirst()
                .orElseThrow();
    }
}
