package com.lunchcatch.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.ops.contract.AuditLogWriter;
import com.lunchcatch.ops.entity.BatchExecutionLog;
import com.lunchcatch.ops.entity.BatchStatus;
import com.lunchcatch.ops.exception.OpsErrorCode;
import com.lunchcatch.ops.exception.OpsException;
import com.lunchcatch.ops.repository.BatchExecutionLogRepository;
import com.lunchcatch.ops.scheduler.BatchJobRegistry;
import com.lunchcatch.ops.scheduler.BatchJobRegistry.Kind;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class BatchExecutionRetryServiceTest {

    private static final String JOB = "DAILY_0000";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 17);
    private static final long ADMIN_ID = 9L;

    @Mock
    private BatchExecutionLogRepository logs;
    @Mock
    private BatchExecutionService executions;
    @Mock
    private BatchJobRegistry registry;
    @Mock
    private AuditLogWriter auditLogWriter;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transaction;

    private BatchExecutionRetryService service;

    @BeforeEach
    void setUp() {
        service = new BatchExecutionRetryService(logs, executions, registry, auditLogWriter, transactionManager);
    }

    private static BatchExecutionLog row(BatchStatus status, long id) {
        BatchExecutionLog row = BatchExecutionLog.running(JOB, DATE, "owner-1");
        ReflectionTestUtils.setField(row, "status", status);
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    @Test
    void 재실행_대상이_아니면_거부한다() {
        when(registry.classify(JOB)).thenReturn(Kind.UNKNOWN);

        assertThatThrownBy(() -> service.retry(ADMIN_ID, JOB, DATE))
                .isInstanceOfSatisfying(OpsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OpsErrorCode.NOT_RETRYABLE_JOB));
        verify(logs, never()).findByJobNameAndBusinessDate(any(), any());
    }

    @Test
    void 실행_기록이_없으면_거부한다() {
        when(registry.classify(JOB)).thenReturn(Kind.BUNDLE);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(ADMIN_ID, JOB, DATE))
                .isInstanceOfSatisfying(OpsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OpsErrorCode.EXECUTION_NOT_FOUND));
        verify(executions, never()).requestRetry(any(), any());
    }

    @Test
    void FAILED_상태가_아니면_거부한다() {
        when(registry.classify(JOB)).thenReturn(Kind.BUNDLE);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE)).thenReturn(Optional.of(row(BatchStatus.RUNNING, 301L)));

        assertThatThrownBy(() -> service.retry(ADMIN_ID, JOB, DATE))
                .isInstanceOfSatisfying(OpsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OpsErrorCode.EXECUTION_NOT_FAILED));
        verify(executions, never()).requestRetry(any(), any());
    }

    /*
     * 동시에 두 요청이 오면 둘 다 FAILED 를 읽을 수 있지만, BatchExecutionService.requestRetry 의
     * 조건부 UPDATE 는 한쪽만 걸린다. 그 결과가 최종 판정이다.
     */
    @Test
    void 조건부_갱신이_걸리지_않으면_거부하고_감사_로그를_남기지_않는다() {
        when(registry.classify(JOB)).thenReturn(Kind.BUNDLE);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE)).thenReturn(Optional.of(row(BatchStatus.FAILED, 301L)));
        when(executions.requestRetry(JOB, DATE)).thenReturn(false);

        assertThatThrownBy(() -> service.retry(ADMIN_ID, JOB, DATE))
                .isInstanceOfSatisfying(OpsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OpsErrorCode.EXECUTION_NOT_FAILED));
        verify(auditLogWriter, never()).write(any(), any(), any(), any());
    }

    /*
     * 앱 서버는 묶음을 돌리지 않는다(#1231). 행을 되돌리고 감사 로그를 남긴 뒤 바로 돌려주고,
     * 배치 서버의 이어받기가 그 행을 집어 배치 설정의 시간 제한으로 돌린다.
     */
    @Test
    void 성공하면_행_되돌리기와_감사_로그를_한_트랜잭션에서_하고_실행_ID를_돌려준다() {
        when(registry.classify(JOB)).thenReturn(Kind.BUNDLE);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.FAILED, 301L)))
                .thenReturn(Optional.of(row(BatchStatus.RUNNING, 301L)));
        when(executions.requestRetry(JOB, DATE)).thenReturn(true);
        when(transactionManager.getTransaction(any())).thenReturn(transaction);

        long runId = service.retry(ADMIN_ID, JOB, DATE);

        assertThat(runId).isEqualTo(301L);
        InOrder order = inOrder(transactionManager, executions, auditLogWriter);
        order.verify(transactionManager).getTransaction(any());
        order.verify(executions).requestRetry(JOB, DATE);
        order.verify(auditLogWriter).write(ADMIN_ID, "BATCH_EXECUTION_RETRY", JOB + " " + DATE, "관리자 수동 재실행");
        order.verify(transactionManager).commit(transaction);
    }

    /*
     * 감사 로그를 남기지 못하면 같은 트랜잭션의 행 되돌리기도 롤백되어 FAILED 로 남는다.
     * 배치 서버는 커밋된 RUNNING 행만 보므로 감사 없는 재실행이 돌지 않는다.
     */
    @Test
    void 감사_로그를_남기지_못하면_트랜잭션을_롤백하고_예외를_올린다() {
        when(registry.classify(JOB)).thenReturn(Kind.BUNDLE);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE)).thenReturn(Optional.of(row(BatchStatus.FAILED, 301L)));
        when(executions.requestRetry(JOB, DATE)).thenReturn(true);
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        doThrow(new IllegalStateException("감사 로그 실패")).when(auditLogWriter).write(any(), any(), any(), any());

        assertThatThrownBy(() -> service.retry(ADMIN_ID, JOB, DATE)).isInstanceOf(IllegalStateException.class);
        verify(transactionManager).rollback(transaction);
        verify(transactionManager, never()).commit(any());
    }
}
