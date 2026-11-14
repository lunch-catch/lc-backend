package com.lunchcatch.ops.service;

import com.lunchcatch.ops.contract.AuditLogWriter;
import com.lunchcatch.ops.entity.BatchExecutionLog;
import com.lunchcatch.ops.entity.BatchStatus;
import com.lunchcatch.ops.exception.OpsErrorCode;
import com.lunchcatch.ops.exception.OpsException;
import com.lunchcatch.ops.repository.BatchExecutionLogRepository;
import com.lunchcatch.ops.scheduler.BatchJobRegistry;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 관리자의 수동 배치 재실행이다(#362).
 *
 * 요청은 행을 되돌리고 감사 로그를 남긴 뒤 바로 끝난다. 실제 재실행은 이 서버가 하지 않는다.
 * 배치 서버의 이어받기(DailyJobScheduler.takeOverStale)가 소유자가 요청 표식인 행을 다음 스캔에 집어 돈다(#1231).
 *
 * 앱 서버에서 돌리면 묶음이 요청용 시간 제한(쿼리와 트랜잭션 5초)을 받아, 5초를 넘는 단계가 있는 묶음은
 * 재실행으로 복구되지 않는다. 앱 서버는 생존 신호도 보내지 않아 2분을 넘는 재실행은 배치 서버가 멈춘 것으로
 * 보고 한 번 더 돌릴 수 있었다. 배치 서버에서 돌리면 배치 설정의 시간 제한과 생존 신호를 그대로 받는다.
 */
@Service
public class BatchExecutionRetryService {

    private static final String AUDIT_ACTION = "BATCH_EXECUTION_RETRY";

    private final BatchExecutionLogRepository logs;
    private final BatchExecutionService executions;
    private final BatchJobRegistry registry;
    private final AuditLogWriter auditLogWriter;
    private final TransactionTemplate transactions;

    public BatchExecutionRetryService(
            BatchExecutionLogRepository logs,
            BatchExecutionService executions,
            BatchJobRegistry registry,
            AuditLogWriter auditLogWriter,
            PlatformTransactionManager transactionManager) {
        this.logs = logs;
        this.executions = executions;
        this.registry = registry;
        this.auditLogWriter = auditLogWriter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /*
     * FAILED 행을 재실행 요청 상태(RUNNING, 소유자는 요청 표식)로 되돌리고 실행 기록 ID 를 돌려준다.
     *
     * jobName 이 00:00 묶음도 등록된 StandaloneJob 도 아니면 422 다. 그 둘이 아니면 이어받기가
     * 다시 돌릴 경로가 없어 상태만 되돌려도 영원히 RUNNING 에 머문다(BatchJobRegistry.Kind.isRetryable).
     *
     * 행이 없으면 404, FAILED 가 아니면 409 다. 동시에 두 번 와도 BatchExecutionService.requestRetry
     * 의 조건부 UPDATE 가 한 번만 걸리게 해서, 뒤에 온 요청은 여기서 409 로 떨어진다.
     *
     * 행 되돌리기와 감사 로그를 한 트랜잭션에 둔다. 감사 로그를 남기지 못하면 되돌리기도 롤백되어
     * FAILED 로 남는다. 배치 서버는 커밋된 행만 보므로 감사 없는 재실행이 돌지 않는다.
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
        transactions.executeWithoutResult(status -> {
            if (!executions.requestRetry(jobName, businessDate)) {
                throw new OpsException(OpsErrorCode.EXECUTION_NOT_FAILED);
            }
            auditLogWriter.write(adminId, AUDIT_ACTION, jobName + " " + businessDate, "관리자 수동 재실행");
        });
        return row.getId();
    }
}
