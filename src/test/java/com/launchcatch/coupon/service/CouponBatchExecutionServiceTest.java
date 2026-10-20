package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.coupon.CouponBatchTask;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

class CouponBatchExecutionServiceTest {

    private static final CouponBatchTask TASK = CouponBatchTask.VERIFY;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
    private final BatchExecutionService executions = mock(BatchExecutionService.class);
    private final BatchExecutionQueryService query = mock(BatchExecutionQueryService.class);
    private final RetryBackoff backoff = mock(RetryBackoff.class);
    private final IntSupplier operation = mock(IntSupplier.class);
    private final CouponBatchExecutionService service = new CouponBatchExecutionService(executions, query, backoff);

    @Test
    void 점유한_실행_세대와_처리_건수로_성공을_기록한다() {
        when(query.findByBusinessDate(DATE)).thenReturn(List.of(record("OTHER", 1), record(TASK.jobName(), 3)));

        service.runClaimed(TASK, DATE, attemptNo -> attemptNo * 2);

        verify(executions).succeedAttempt(TASK.jobName(), DATE, 3, 6);
        verifyNoInteractions(backoff);
    }

    @Test
    void 일반_오류도_두번_재시도하고_세번째_성공을_반환한다() {
        retryAllowed();
        when(operation.getAsInt()).thenThrow(new IllegalStateException("first"))
                .thenThrow(new IllegalStateException("second")).thenReturn(7);

        assertThat(service.retry(TASK, DATE, 3, operation)).isEqualTo(7);

        verify(operation, times(3)).getAsInt();
        verify(backoff).pause(1);
        verify(backoff).pause(2);
        verify(executions, times(2)).renewAttempt(TASK.jobName(), DATE, 3);
    }

    @Test
    void DB가_허용해도_추가_두번을_넘겨_시도하지_않는다() {
        retryAllowed();
        when(operation.getAsInt()).thenThrow(new IllegalStateException("failed"));

        assertThatThrownBy(() -> service.retry(TASK, DATE, 3, operation)).hasMessage("failed");

        verify(operation, times(3)).getAsInt();
        verify(executions, times(2)).recordRetryAttempt(eq(TASK.jobName()), eq(DATE), eq(3), anyString());
        verify(backoff, never()).pause(3);
    }

    @Test
    void 기존_실행이_재시도를_소진했으면_기다리거나_다시_실행하지_않는다() {
        when(operation.getAsInt()).thenThrow(new IllegalStateException("failed"));

        assertThatThrownBy(() -> service.retry(TASK, DATE, 3, operation)).hasMessage("failed");

        verify(operation).getAsInt();
        verifyNoInteractions(backoff);
    }

    @Test
    void 대기가_중단되면_실행권을_갱신하거나_업무를_더_실행하지_않는다() {
        when(executions.recordRetryAttempt(eq(TASK.jobName()), eq(DATE), eq(3), anyString())).thenReturn(true);
        when(operation.getAsInt()).thenThrow(new IllegalStateException("failed"));

        assertThatThrownBy(() -> service.retry(TASK, DATE, 3, operation)).hasMessage("failed");

        verify(operation).getAsInt();
        verify(executions, never()).renewAttempt(TASK.jobName(), DATE, 3);
    }

    @Test
    void 재시도할_실행권을_잃으면_추가_처리를_멈춘다() {
        when(executions.recordRetryAttempt(eq(TASK.jobName()), eq(DATE), eq(3), anyString())).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        when(operation.getAsInt()).thenThrow(new IllegalStateException("failed"));

        assertThatThrownBy(() -> service.retry(TASK, DATE, 3, operation)).hasMessage("failed");

        verify(operation).getAsInt();
    }

    @Test
    void 오류로_끝나면_점유한_세대로만_실패를_기록한다() {
        when(query.findByBusinessDate(DATE)).thenReturn(List.of(record(TASK.jobName(), 3)));

        service.runClaimed(TASK, DATE, attemptNo -> { throw new IllegalStateException("failed"); });

        verify(executions).failAttempt(TASK.jobName(), DATE, 3, "IllegalStateException: failed");
    }

    @Test
    void 실행_세대를_못_읽으면_다른_세대의_상태를_닫지_않는다() {
        when(query.findByBusinessDate(DATE)).thenThrow(new IllegalStateException("query failed"));

        service.runClaimed(TASK, DATE, attemptNo -> { throw new AssertionError("업무를 실행하면 안 된다"); });

        verifyNoInteractions(executions, backoff);
    }

    @Test
    void 실패_기록의_오류도_원래_오류에_함께_남긴다() {
        when(query.findByBusinessDate(DATE)).thenReturn(List.of(record(TASK.jobName(), 3)));
        IllegalStateException failure = new IllegalStateException("failed");
        IllegalStateException recordFailure = new IllegalStateException("record failed");
        doThrow(recordFailure).when(executions).failAttempt(TASK.jobName(), DATE, 3, "IllegalStateException: failed");

        service.runClaimed(TASK, DATE, attemptNo -> { throw failure; });

        assertThat(failure.getSuppressed()).containsExactly(recordFailure);
    }

    private void retryAllowed() {
        when(executions.recordRetryAttempt(eq(TASK.jobName()), eq(DATE), eq(3), anyString())).thenReturn(true);
        when(executions.renewAttempt(TASK.jobName(), DATE, 3)).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        when(backoff.pause(2)).thenReturn(true);
    }

    private BatchExecutionRecord record(String name, int attemptNo) {
        return new BatchExecutionRecord(1L, name, DATE, "RUNNING", DATE.atStartOfDay(),
                null, null, "batch-1", 0, null, attemptNo);
    }
}
