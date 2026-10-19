package com.launchcatch.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.ops.contract.AuditLogWriter;
import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.entity.BatchStatus;
import com.launchcatch.ops.exception.OpsErrorCode;
import com.launchcatch.ops.exception.OpsException;
import com.launchcatch.ops.repository.BatchExecutionLogRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

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
    private DailyJobRunner runner;
    @Mock
    private AuditLogWriter auditLogWriter;
    @Mock
    private PlatformTransactionManager transactionManager;

    private final Executor direct = Runnable::run;

    private BatchExecutionRetryService service(Executor executor) {
        return new BatchExecutionRetryService(logs, executions, runner, auditLogWriter, transactionManager, executor);
    }

    private static BatchExecutionLog row(BatchStatus status, long id, int attemptNo) {
        BatchExecutionLog row = BatchExecutionLog.running(JOB, DATE, "owner-1");
        ReflectionTestUtils.setField(row, "status", status);
        ReflectionTestUtils.setField(row, "id", id);
        ReflectionTestUtils.setField(row, "attemptNo", attemptNo);
        return row;
    }

    @Test
    void 재실행_대상이_아니면_거부한다() {
        when(runner.isRetryable(JOB)).thenReturn(false);

        assertThatThrownBy(() -> service(direct).retry(ADMIN_ID, JOB, DATE))
                .isInstanceOfSatisfying(OpsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OpsErrorCode.NOT_RETRYABLE_JOB));
        verify(logs, never()).findByJobNameAndBusinessDate(any(), any());
    }

    @Test
    void 실행_기록이_없으면_거부한다() {
        when(runner.isRetryable(JOB)).thenReturn(true);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(direct).retry(ADMIN_ID, JOB, DATE))
                .isInstanceOfSatisfying(OpsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OpsErrorCode.EXECUTION_NOT_FOUND));
        verify(executions, never()).retry(any(), any());
    }

    @Test
    void FAILED_상태가_아니면_거부한다() {
        when(runner.isRetryable(JOB)).thenReturn(true);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.RUNNING, 301L, 1)));

        assertThatThrownBy(() -> service(direct).retry(ADMIN_ID, JOB, DATE))
                .isInstanceOfSatisfying(OpsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OpsErrorCode.EXECUTION_NOT_FAILED));
        verify(executions, never()).retry(any(), any());
    }

    /*
     * 동시에 두 요청이 오면 둘 다 FAILED 를 읽을 수 있지만, BatchExecutionService.retry 의
     * 조건부 UPDATE 는 한쪽만 걸린다. 그 결과가 최종 판정이다.
     */
    @Test
    void 조건부_갱신이_걸리지_않으면_거부한다() {
        when(runner.isRetryable(JOB)).thenReturn(true);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.FAILED, 301L, 1)));
        when(executions.retry(JOB, DATE)).thenReturn(false);

        assertThatThrownBy(() -> service(direct).retry(ADMIN_ID, JOB, DATE))
                .isInstanceOfSatisfying(OpsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OpsErrorCode.EXECUTION_NOT_FAILED));
        verify(auditLogWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    void 성공하면_감사_로그를_남기고_재개를_시작시키고_실행_ID를_돌려준다() {
        when(runner.isRetryable(JOB)).thenReturn(true);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.FAILED, 301L, 1)))
                .thenReturn(Optional.of(row(BatchStatus.RUNNING, 301L, 2)));
        when(executions.retry(JOB, DATE)).thenReturn(true);

        long runId = service(direct).retry(ADMIN_ID, JOB, DATE);

        assertThat(runId).isEqualTo(301L);
        verify(auditLogWriter).write(ADMIN_ID, "BATCH_EXECUTION_RETRY", JOB + " " + DATE, "관리자 수동 재실행");
        verify(runner).resume(JOB, DATE, 2);
    }

    @Test
    void 실행기가_가득_차_재개를_시작하지_못하면_실패로_닫고_예외를_올린다() {
        when(runner.isRetryable(JOB)).thenReturn(true);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.FAILED, 301L, 1)))
                .thenReturn(Optional.of(row(BatchStatus.RUNNING, 301L, 2)));
        when(executions.retry(JOB, DATE)).thenReturn(true);
        Executor full = task -> {
            throw new RejectedExecutionException("가득 참");
        };

        assertThatThrownBy(() -> service(full).retry(ADMIN_ID, JOB, DATE))
                .isInstanceOf(RejectedExecutionException.class);
        verify(executions).fail(JOB, DATE, "수동 재실행을 시작하지 못했다");
    }

    @Test
    void 감사_로그를_남기지_못하면_재개하지_않고_실패로_닫고_예외를_올린다() {
        when(runner.isRetryable(JOB)).thenReturn(true);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.FAILED, 301L, 1)))
                .thenReturn(Optional.of(row(BatchStatus.RUNNING, 301L, 2)));
        when(executions.retry(JOB, DATE)).thenReturn(true);
        doThrow(new IllegalStateException("감사 로그 실패")).when(auditLogWriter)
                .write(any(), any(), any(), any());

        assertThatThrownBy(() -> service(direct).retry(ADMIN_ID, JOB, DATE))
                .isInstanceOf(IllegalStateException.class);
        verify(executions).fail(JOB, DATE, "수동 재실행을 시작하지 못했다");
        verify(runner, never()).resume(any(), any(), anyInt());
    }

    @Test
    void 서비스가_직접_만든_실행기에서_다른_스레드로_재개를_돌린다() {
        when(runner.isRetryable(JOB)).thenReturn(true);
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.FAILED, 301L, 1)))
                .thenReturn(Optional.of(row(BatchStatus.RUNNING, 301L, 2)));
        when(executions.retry(JOB, DATE)).thenReturn(true);
        BatchExecutionRetryService owned = new BatchExecutionRetryService(
                logs, executions, runner, auditLogWriter, transactionManager);

        long runId = owned.retry(ADMIN_ID, JOB, DATE);
        owned.shutdown();

        assertThat(runId).isEqualTo(301L);
        verify(runner, timeout(5000)).resume(JOB, DATE, 2);
    }

    @Test
    void 실행기를_받은_생성자로_만든_서비스는_내릴_실행기가_없어도_내려진다() {
        assertThatCode(() -> service(direct).shutdown()).doesNotThrowAnyException();
    }
}
