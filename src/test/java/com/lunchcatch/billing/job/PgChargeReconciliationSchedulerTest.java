package com.lunchcatch.billing.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.service.PgChargeReconciliationService;
import com.lunchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

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
        // 이어받기 경로는 영업일을 넘겨받아 묻지 않는다
        lenient().when(executions.businessDate()).thenReturn(BUSINESS_DATE);
    }

    /*
     * 워터마크가 5분이라 00:00 에 돌면 전일 23:55 ~ 24:00 결제가 빠진다. 워터마크가 지난 뒤에 돌아야 전일 전체가 대상이 된다.
     */
    @Test
    @DisplayName("01:30 KST 에 돈다")
    void 영업일_경계의_워터마크가_지난_01시_30분에_돈다() throws NoSuchMethodException {
        Scheduled scheduled = PgChargeReconciliationScheduler.class.getMethod("reconcile")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 30 1 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
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

    /*
     * 멈춘 실행을 이어받은 서버와 관리자 수동 재실행이 이 길로 다시 돌린다.
     * 행을 닫는 일은 BatchJobRunner 가 한다.
     */
    @Test
    @DisplayName("이어받은 서버가 부르면 넘겨받은 영업일로 대사하고 행은 직접 닫지 않는다")
    void 이어받은_서버가_부르면_대사를_돌리고_행은_닫지_않는다() {
        scheduler.run(BUSINESS_DATE, 2);

        verify(reconciliationService).reconcilePreviousDay(BUSINESS_DATE, PgChargeReconciliationScheduler.JOB_NAME);
        verifyNoInteractions(executions);
        assertThat(scheduler.jobName()).isEqualTo("BILLING_PG_VS_CHARGE_RECON");
    }
}
