package com.launchcatch.ops.service;

import com.launchcatch.ops.contract.AuditLogWriter;
import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.entity.BatchStatus;
import com.launchcatch.ops.exception.OpsErrorCode;
import com.launchcatch.ops.exception.OpsException;
import com.launchcatch.ops.repository.BatchExecutionLogRepository;
import com.launchcatch.ops.scheduler.BatchJobRegistry;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 관리자의 수동 배치 재실행이다(#362).
 *
 * 상태 전환과 감사 로그는 요청 안에서 끝내고, 실제 재실행(BatchJobRunner.resume)은
 * 별도 스레드에서 돈다. 분석의 수동 재계산(A-05, AggregationRecalculationService)과 같은
 * 선례다. 00:00 묶음은 여러 단계가 걸려 오래 걸릴 수 있어 요청 스레드를 묶어 두지 않는다.
 *
 * 실행기를 스프링 빈으로 노출하지 않는다. Executor 빈이 하나라도 있으면 부트가 기본 작업
 * 실행기를 만들지 않아, 뒤에 붙을 다른 @Async 호출이 이 단일 스레드 실행기를 쓰게 된다.
 */
@Slf4j
@Service
public class BatchExecutionRetryService {

    private static final String AUDIT_ACTION = "BATCH_EXECUTION_RETRY";
    private static final int QUEUE_CAPACITY = 4;

    private final BatchExecutionLogRepository logs;
    private final BatchExecutionService executions;
    private final BatchJobRunner runner;
    private final BatchJobRegistry registry;
    private final AuditLogWriter auditLogWriter;
    private final TransactionTemplate transactions;
    private final Executor executor;
    private final ThreadPoolExecutor ownedExecutor;

    @Autowired
    public BatchExecutionRetryService(
            BatchExecutionLogRepository logs,
            BatchExecutionService executions,
            BatchJobRunner runner,
            BatchJobRegistry registry,
            AuditLogWriter auditLogWriter,
            PlatformTransactionManager transactionManager) {
        this.logs = logs;
        this.executions = executions;
        this.runner = runner;
        this.registry = registry;
        this.auditLogWriter = auditLogWriter;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ownedExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                daemonThreads(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor = ownedExecutor;
    }

    // 테스트가 재실행을 호출 스레드에서 바로 돌리려고 쓴다.
    BatchExecutionRetryService(
            BatchExecutionLogRepository logs,
            BatchExecutionService executions,
            BatchJobRunner runner,
            BatchJobRegistry registry,
            AuditLogWriter auditLogWriter,
            PlatformTransactionManager transactionManager,
            Executor executor) {
        this.logs = logs;
        this.executions = executions;
        this.runner = runner;
        this.registry = registry;
        this.auditLogWriter = auditLogWriter;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ownedExecutor = null;
        this.executor = executor;
    }

    /*
     * FAILED 행을 RUNNING 으로 되돌리고 실행 기록 ID 를 돌려준다.
     *
     * jobName 이 00:00 묶음도 등록된 StandaloneJob 도 아니면 422 다. 그 둘이 아니면 재개할
     * 경로가 없어 상태만 되돌려도 영원히 RUNNING 에 머문다(BatchJobRegistry.Kind.isRetryable).
     *
     * 행이 없으면 404, FAILED 가 아니면 409 다. 동시에 두 번 와도 BatchExecutionService.retry
     * 의 조건부 UPDATE 가 한 번만 걸리게 해서, 뒤에 온 요청은 여기서 409 로 떨어진다.
     */
    public long retry(Long adminId, String jobName, LocalDate businessDate) {
        if (!registry.classify(jobName).isRetryable()) {
            throw new OpsException(OpsErrorCode.NOT_RETRYABLE_JOB);
        }
        BatchExecutionLog row = logs.findByJobNameAndBusinessDate(jobName, businessDate)
                .orElseThrow(() -> new OpsException(OpsErrorCode.EXECUTION_NOT_FOUND));
        if (row.getStatus() != BatchStatus.FAILED) {
            throw new OpsException(OpsErrorCode.EXECUTION_NOT_FAILED);
        }
        if (!executions.retry(jobName, businessDate)) {
            throw new OpsException(OpsErrorCode.EXECUTION_NOT_FAILED);
        }
        /*
         * retry 는 벌크 UPDATE 라 영속성 컨텍스트를 비운다. 올린 attemptNo 는 이 요청 안에서
         * 다시 읽어야 보인다(BatchExecutionService.takeOverStale 과 같은 이유).
         */
        BatchExecutionLog reloaded = logs.findByJobNameAndBusinessDate(jobName, businessDate).orElseThrow();
        start(adminId, jobName, businessDate, reloaded.getAttemptNo());
        return reloaded.getId();
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    private static CustomizableThreadFactory daemonThreads() {
        CustomizableThreadFactory factory = new CustomizableThreadFactory("ops-batch-retry-");
        factory.setDaemon(true);
        return factory;
    }

    /*
     * 감사 로그를 남기고 재개를 시작시킨다. 둘 중 하나라도 실패하면 되돌린 행을 다시 실패로
     * 닫는다. 그러지 않으면 아무도 돌리지 않는 RUNNING 행이 영원히 남는다.
     *
     * 감사 로그 기록(AuditLogWriter)은 호출자의 트랜잭션에 반드시 참여해야 한다(MANDATORY).
     * retry 의 조건부 UPDATE 는 트랜잭션 밖에서 돌아야 해서 요청 전체를 트랜잭션으로 묶지
     * 않고, 기록 한 번만 따로 트랜잭션에 넣는다.
     */
    private void start(Long adminId, String jobName, LocalDate businessDate, int attemptNo) {
        try {
            transactions.executeWithoutResult(status -> auditLogWriter.write(
                    adminId, AUDIT_ACTION, jobName + " " + businessDate, "관리자 수동 재실행"));
            // 직접 만든 실행기의 스레드에는 요청의 MDC(traceId 등)가 없으므로 복사해 넘긴다(LOG-6-07).
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            executor.execute(() -> resumeWithMdc(mdc, jobName, businessDate, attemptNo));
        } catch (RuntimeException failed) {
            executions.fail(jobName, businessDate, "수동 재실행을 시작하지 못했다");
            throw failed;
        }
    }

    // 실행기 스레드에는 요청의 MDC 가 없어, 넘겨받은 값을 심고 resume 을 부른 뒤 원래 MDC 로 되돌린다.
    private void resumeWithMdc(Map<String, String> mdc, String jobName, LocalDate businessDate, int attemptNo) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            } else {
                MDC.clear();
            }
            resume(jobName, businessDate, attemptNo);
        } finally {
            // 실행기가 호출 스레드에서 도는 경우(테스트 등)에도 호출자의 MDC 를 되돌려 놓는다.
            if (previous != null) {
                MDC.setContextMap(previous);
            } else {
                MDC.clear();
            }
        }
    }

    /*
     * 별도 스레드에서 도는 재개다. runner.resume 이 던지는 예외는 ThreadPoolExecutor 작업
     * 스레드에서 잡히지 않으면 JVM 기본 핸들러가 System.err 로만 남겨 구조화 로그를 거치지
     * 않는다. 그 사이 행은 이 서버 소유의 RUNNING 으로 남아 생존 신호가 계속 갱신하므로
     * 이어받기도 가져가지 않는다. 운영자가 원인을 찾을 단서가 이 로그뿐이라 여기서 남긴다.
     */
    private void resume(String jobName, LocalDate businessDate, int attemptNo) {
        try {
            runner.resume(jobName, businessDate, attemptNo);
        } catch (RuntimeException e) {
            log.error("event=BATCH_MANUAL_RESUME_FAILED job={} businessDate={} attemptNo={}",
                    jobName, businessDate, attemptNo, e);
        }
    }
}
