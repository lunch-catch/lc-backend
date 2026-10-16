package com.launchcatch.billing.job;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.service.PgChargeReconciliationService;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 배치 서버가 두 대라 같은 시각에 둘 다 이 작업을 시도한다. 겹치지 않는 것은
 * batch_execution_log 의 작업 점유가 보장한다(배치 운영 문서 2장).
 */
@ExtendWith(MockitoExtension.class)
class PgChargeReconciliationSchedulerTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 16);

    @Mock PgChargeReconciliationService reconciliationService;
    @Mock BatchExecutionService executions;

    private PgChargeReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PgChargeReconciliationScheduler(reconciliationService, executions);
        when(executions.businessDate()).thenReturn(BUSINESS_DATE);
    }

    @Test
    @DisplayName("점유에 성공하면 대사를 돌리고 성공으로 닫는다")
    void 점유에_성공하면_대사를_돌리고_성공으로_닫는다() {
        when(executions.claim(PgChargeReconciliationScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(true);

        scheduler.reconcile();

        verify(reconciliationService).reconcilePreviousDay(BUSINESS_DATE, PgChargeReconciliationScheduler.JOB_NAME);
        verify(executions).succeed(PgChargeReconciliationScheduler.JOB_NAME, BUSINESS_DATE);
    }

    @Test
    @DisplayName("다른 배치 서버가 점유했으면 돌리지 않는다")
    void 다른_배치_서버가_점유했으면_돌리지_않는다() {
        when(executions.claim(PgChargeReconciliationScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(false);

        scheduler.reconcile();

        verify(reconciliationService, never()).reconcilePreviousDay(BUSINESS_DATE,
                PgChargeReconciliationScheduler.JOB_NAME);
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE));
    }

    @Test
    @DisplayName("대사가 터지면 실패로 닫는다")
    void 대사가_터지면_실패로_닫는다() {
        when(executions.claim(PgChargeReconciliationScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("boom");
        doThrow(failure).when(reconciliationService).reconcilePreviousDay(BUSINESS_DATE,
                PgChargeReconciliationScheduler.JOB_NAME);

        assertThatThrownBy(scheduler::reconcile).isSameAs(failure);

        verify(executions).fail(eq(PgChargeReconciliationScheduler.JOB_NAME), eq(BUSINESS_DATE), anyString());
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE));
    }

    @Test
    void 실패_기록까지_실패해도_원래_예외를_유지한다() {
        when(executions.claim(PgChargeReconciliationScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("원래 실패");
        IllegalStateException recordFailure = new IllegalStateException("실패 기록 오류");
        doThrow(failure).when(reconciliationService).reconcilePreviousDay(BUSINESS_DATE,
                PgChargeReconciliationScheduler.JOB_NAME);
        doThrow(recordFailure).when(executions)
                .fail(eq(PgChargeReconciliationScheduler.JOB_NAME), eq(BUSINESS_DATE), anyString());

        assertThatThrownBy(scheduler::reconcile).isSameAs(failure).hasSuppressedException(recordFailure);

        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE));
    }
}
