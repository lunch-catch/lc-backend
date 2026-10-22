package com.launchcatch.billing.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 분석 01:00 집계는 (BILLING_DAILY_RELEASE, 영업일) 의 SUCCESS 로 그 영업일 원천이 확정됐는지 본다
@ExtendWith(MockitoExtension.class)
class DailyReleaseMarkerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    private static final String JOB = DailyReleaseMarker.RELEASE_JOB_NAME;

    @Mock BatchExecutionService executions;

    private DailyReleaseMarker marker;

    @BeforeEach
    void setUp() {
        marker = new DailyReleaseMarker(executions);
    }

    @Test
    void 표시가_없으면_성공으로_남긴다() {
        when(executions.begin(JOB, DAY)).thenReturn(StepOutcome.STARTED);

        marker.mark(DAY);

        verify(executions).succeed(JOB, DAY);
    }

    @Test
    void 이미_표시가_있으면_다시_닫지_않는다() {
        when(executions.begin(JOB, DAY)).thenReturn(StepOutcome.ALREADY_DONE);

        marker.mark(DAY);

        verify(executions, never()).succeed(any(), any());
    }

    @Test
    void 막힌_행은_건드리지_않는다() {
        when(executions.begin(JOB, DAY)).thenReturn(StepOutcome.BLOCKED);
        when(executions.retry(JOB, DAY)).thenReturn(false);

        marker.mark(DAY);

        verify(executions, never()).succeed(any(), any());
    }

    @Test
    void 해제는_끝났지만_실패로_남은_표시는_다시_열어_성공으로_닫는다() {
        when(executions.begin(JOB, DAY)).thenReturn(StepOutcome.BLOCKED, StepOutcome.STARTED);
        when(executions.retry(JOB, DAY)).thenReturn(true);

        marker.mark(DAY);

        verify(executions).succeed(JOB, DAY);
    }
}
