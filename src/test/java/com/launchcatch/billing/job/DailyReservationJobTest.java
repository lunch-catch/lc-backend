package com.launchcatch.billing.job;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.service.DailyReservationService;
import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 분석 01:00 집계는 (BILLING_DAILY_RELEASE, 전날) 의 SUCCESS 로 전날 원천이 확정됐는지 본다
@ExtendWith(MockitoExtension.class)
class DailyReservationJobTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate PREV = LocalDate.of(2026, 10, 19);

    @Mock DailyReservationService reservation;
    @Mock BatchExecutionService executions;

    private DailyReservationJob job;

    @BeforeEach
    void setUp() {
        job = new DailyReservationJob(reservation, executions);
    }

    @Test
    void 예약이_끝나면_전날_해제_표시를_성공으로_남긴다() {
        when(executions.begin(DailyReservationJob.RELEASE_JOB_NAME, PREV)).thenReturn(StepOutcome.STARTED);

        job.run(DAY);

        InOrder order = inOrder(reservation, executions);
        order.verify(reservation).reserve(DAY);
        order.verify(executions).succeed(DailyReservationJob.RELEASE_JOB_NAME, PREV);
    }

    @Test
    void 이미_표시가_있으면_다시_닫지_않는다() {
        when(executions.begin(DailyReservationJob.RELEASE_JOB_NAME, PREV)).thenReturn(StepOutcome.ALREADY_DONE);

        job.run(DAY);

        verify(executions, never()).succeed(any(), any());
    }

    @Test
    void 막힌_행은_건드리지_않는다() {
        when(executions.begin(DailyReservationJob.RELEASE_JOB_NAME, PREV)).thenReturn(StepOutcome.BLOCKED);
        when(executions.retry(DailyReservationJob.RELEASE_JOB_NAME, PREV)).thenReturn(false);

        job.run(DAY);

        verify(executions, never()).succeed(any(), any());
    }

    @Test
    void 해제는_끝났지만_실패로_남은_표시는_다시_열어_성공으로_닫는다() {
        when(executions.begin(DailyReservationJob.RELEASE_JOB_NAME, PREV))
                .thenReturn(StepOutcome.BLOCKED, StepOutcome.STARTED);
        when(executions.retry(DailyReservationJob.RELEASE_JOB_NAME, PREV)).thenReturn(true);

        job.run(DAY);

        verify(executions).succeed(DailyReservationJob.RELEASE_JOB_NAME, PREV);
    }

    @Test
    void 예약이_실패하면_표시를_남기지_않는다() {
        doThrow(new IllegalStateException("예약 실패")).when(reservation).reserve(DAY);

        assertThatThrownBy(() -> job.run(DAY)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(executions);
    }
}
