package com.launchcatch.ops.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.service.BatchExecutionService;
import com.launchcatch.ops.service.DailyJobRunner;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 트리거(스케줄, 이어받기 스캔)가 DailyJobRunner 에 올바르게 위임하는지만 본다.
 *
 * 묶음을 실제로 도는 로직(순서, 실패 전파, 재실행)과 재개 경로(resume)의 동작 자체는
 * DailyJobRunnerTest 가 본다. 계층 규칙(Service 는 Entry 를 부를 수 없다, 설계 문서 1.5절)
 * 때문에 실행 로직을 DailyJobRunner(Service)로 옮기면서 이 클래스는 얇아졌다(#362).
 */
@ExtendWith(MockitoExtension.class)
class DailyJobSchedulerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 6);
    private static final String FIRST = "LOAD_DAILY_CANDIDATE";

    @Mock
    private BatchExecutionService executions;

    @Mock
    private DailyJobRunner runner;

    private DailyJobScheduler scheduler() {
        return new DailyJobScheduler(runner, executions);
    }

    private BatchExecutionLog row(String jobName, int attemptNo) {
        BatchExecutionLog row = BatchExecutionLog.running(jobName, DATE, "batch-2");
        ReflectionTestUtils.setField(row, "attemptNo", attemptNo);
        return row;
    }

    @Test
    @DisplayName("다른 서버가 묶음을 점유했으면 runner를 건드리지 않는다")
    void 점유에_실패하면_돌지_않는다() {
        when(executions.businessDate()).thenReturn(DATE);
        when(executions.claim(DailyJobRunner.BUNDLE, DATE)).thenReturn(false);

        scheduler().runDailyBundle();

        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("묶음을 점유하면 runner에게 묶음을 돌리게 한다")
    void 점유하면_runner에게_맡긴다() {
        when(executions.businessDate()).thenReturn(DATE);
        when(executions.claim(DailyJobRunner.BUNDLE, DATE)).thenReturn(true);

        scheduler().runDailyBundle();

        verify(runner).runBundle(DATE);
    }

    @Test
    @DisplayName("생존 신호는 내가 들고 있는 행을 갱신한다")
    void 생존_신호() {
        scheduler().sendHeartbeat();

        verify(executions).renewOwned();
    }

    @Test
    @DisplayName("이어받은 행이 묶음의 단계면 따로 손대지 않는다")
    void 단계는_따로_손대지_않는다() {
        when(executions.takeOverStale()).thenReturn(List.of(row(FIRST, 1)));
        when(runner.isStep(FIRST)).thenReturn(true);

        scheduler().takeOverStale();

        verify(runner, never()).resume(any(), any(), anyInt());
        verify(executions, never()).fail(any(), any(), any());
    }

    @Test
    @DisplayName("이어받은 행이 다시 실행할 수 있는 작업이면 세대와 함께 재개시킨다")
    void 재개할_수_있으면_resume을_부른다() {
        when(executions.takeOverStale()).thenReturn(List.of(row(DailyJobRunner.BUNDLE, 2)));
        when(runner.isStep(DailyJobRunner.BUNDLE)).thenReturn(false);
        when(runner.isRetryable(DailyJobRunner.BUNDLE)).thenReturn(true);

        scheduler().takeOverStale();

        verify(runner).resume(DailyJobRunner.BUNDLE, DATE, 2);
    }

    @Test
    @DisplayName("다시 돌릴 경로가 없는 작업은 실패로 닫는다")
    void 재개_경로가_없으면_닫는다() {
        BatchExecutionLog orphan = row("EXPIRE_COUPON:42", 1);
        when(executions.takeOverStale()).thenReturn(List.of(orphan));
        when(runner.isStep("EXPIRE_COUPON:42")).thenReturn(false);
        when(runner.isRetryable("EXPIRE_COUPON:42")).thenReturn(false);

        scheduler().takeOverStale();

        verify(executions).fail("EXPIRE_COUPON:42", DATE, "멈춘 실행을 이어받았으나 다시 돌릴 경로가 없다");
        verify(runner, never()).resume(any(), any(), anyInt());
    }

    @Test
    @DisplayName("이어받을 것이 없으면 아무것도 하지 않는다")
    void 이어받을_것이_없다() {
        when(executions.takeOverStale()).thenReturn(List.of());

        scheduler().takeOverStale();

        verifyNoInteractions(runner);
    }
}
