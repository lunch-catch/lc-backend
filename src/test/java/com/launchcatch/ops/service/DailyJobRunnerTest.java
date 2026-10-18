package com.launchcatch.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.ops.scheduler.DailyJob;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.scheduler.StandaloneJob;
import com.launchcatch.ops.scheduler.StepOutcome;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

/*
 * 묶음이 순서를 지키고, 멈춰야 할 때 멈추는지, 그리고 이어받기와 관리자 수동 재실행(#362)이
 * 함께 쓰는 재개 경로(resume)가 실제로 작업을 돌리는지 본다.
 *
 * 여기서 막는 사고는 둘이다. 앞 단계가 실패했는데 뒤 단계가 도는 것, 그리고 점유를 잃은 서버가
 * 남은 단계를 계속 도는 것이다. 둘 다 결과가 두 번 반영되거나 어긋난 상태로 남는다.
 */
@ExtendWith(MockitoExtension.class)
class DailyJobRunnerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 6);
    private static final String FIRST = "LOAD_DAILY_CANDIDATE";
    private static final String SECOND = "RESERVE_DAILY_BUDGET";

    @Mock
    private BatchExecutionService executions;

    @Mock
    private RetryBackoff backoff;

    private DailyJob job(String name) {
        return new DailyJob() {
            @Override
            public String jobName() {
                return name;
            }

            @Override
            public void run(LocalDate businessDate) {
                // 테스트는 호출 여부만 본다
            }
        };
    }

    private DailyJob failing(String name) {
        return new DailyJob() {
            @Override
            public String jobName() {
                return name;
            }

            @Override
            public void run(LocalDate businessDate) {
                throw new IllegalStateException("원본을 읽지 못했다");
            }
        };
    }

    private DailyJobRunner runner(DailyJob... jobs) {
        return new DailyJobRunner(List.of(jobs), List.of(), executions, backoff);
    }

    private DailyJobRunner runnerWithStandalone(StandaloneJob... standaloneJobs) {
        return new DailyJobRunner(List.of(), List.of(standaloneJobs), executions, backoff);
    }

    private StandaloneJob standalone(String name) {
        return new StandaloneJob() {
            @Override
            public String jobName() {
                return name;
            }

            @Override
            public void run(LocalDate businessDate, int attemptNo) {
                // 테스트는 호출 여부만 본다
            }
        };
    }

    private StandaloneJob failingStandaloneTimes(String name, int failures, RuntimeException error) {
        return new StandaloneJob() {
            private int thrown;

            @Override
            public String jobName() {
                return name;
            }

            @Override
            public void run(LocalDate businessDate, int attemptNo) {
                if (thrown++ < failures) {
                    throw error;
                }
            }
        };
    }

    /** 처음 몇 번만 던지고 그 뒤에는 성공하는 작업이다. 자동 재실행을 보려면 상태가 필요하다. */
    private DailyJob failingTimes(String name, int failures, RuntimeException error) {
        return new DailyJob() {
            private int thrown;

            @Override
            public String jobName() {
                return name;
            }

            @Override
            public void run(LocalDate businessDate) {
                if (thrown++ < failures) {
                    throw error;
                }
            }
        };
    }

    private void stubRenew() {
        when(executions.renew(DailyJobRunner.BUNDLE, DATE)).thenReturn(true);
    }

    @Test
    @DisplayName("단계를 순서대로 돌고 묶음을 성공으로 닫는다")
    void 순서대로_돌고_닫는다() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.begin(SECOND, DATE)).thenReturn(StepOutcome.STARTED);

        runner(job(FIRST), job(SECOND)).runBundle(DATE);

        InOrder order = inOrder(executions);
        order.verify(executions).succeed(FIRST, DATE);
        order.verify(executions).succeed(SECOND, DATE);
        order.verify(executions).succeed(DailyJobRunner.BUNDLE, DATE);
    }

    /*
     * 앞 단계가 실패하면 뒤 단계는 돌지 않는다 (규칙 6).
     * 00:00 묶음은 뒤 단계가 앞 단계의 결과를 읽으므로, 그대로 진행하면 빈 입력으로 집계한다.
     */
    @Test
    @DisplayName("앞 단계가 실패하면 뒤 단계를 돌리지 않고 묶음도 실패한다")
    void 앞_단계가_실패하면_멈춘다() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);

        runner(failing(FIRST), job(SECOND)).runBundle(DATE);

        verify(executions).fail(FIRST, DATE, "IllegalStateException: 원본을 읽지 못했다");
        verify(executions).fail(DailyJobRunner.BUNDLE, DATE, FIRST + " 단계가 실패했다");
        verify(executions, never()).begin(SECOND, DATE);
    }

    /*
     * 점유를 잃으면 멈춘다.
     * 이어받은 서버가 이미 같은 단계를 돌고 있어서, 여기서 계속 가면 둘이 겹친다.
     */
    @Test
    @DisplayName("묶음 점유를 잃으면 단계를 시작하지 않는다")
    void 점유를_잃으면_멈춘다() {
        when(executions.renew(DailyJobRunner.BUNDLE, DATE)).thenReturn(false);

        runner(job(FIRST)).runBundle(DATE);

        verify(executions, never()).begin(FIRST, DATE);
        verify(executions, never()).succeed(DailyJobRunner.BUNDLE, DATE);
    }

    @Test
    @DisplayName("이미 끝난 단계는 건너뛰고 다음으로 간다")
    void 끝난_단계는_건너뛴다() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.ALREADY_DONE);
        when(executions.begin(SECOND, DATE)).thenReturn(StepOutcome.STARTED);

        runner(job(FIRST), job(SECOND)).runBundle(DATE);

        verify(executions, never()).succeed(FIRST, DATE);
        verify(executions).succeed(SECOND, DATE);
    }

    @Test
    @DisplayName("막힌 단계를 되돌리지 못하면 묶음을 실패로 닫는다")
    void 막힌_단계를_되돌리지_못하면_묶음을_닫는다() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.BLOCKED);
        when(executions.retry(FIRST, DATE)).thenReturn(false);

        runner(job(FIRST), job(SECOND)).runBundle(DATE);

        verify(executions).fail(DailyJobRunner.BUNDLE, DATE, FIRST + " 단계를 시작할 수 없다");
        verify(executions, never()).begin(SECOND, DATE);
    }

    /*
     * 관리자가 00:00 묶음을 수동 재실행하면 묶음 행만 RUNNING으로 되돌아가고, 실제로 실패했던
     * 단계 행은 여전히 FAILED라 begin이 BLOCKED를 돌려준다(#362 코드래빗 리뷰로 찾음). 그
     * 단계도 retry로 되돌려야 다시 실행되고, 되돌리지 않으면 관리자가 재실행을 눌러도 항상
     * 같은 자리에서 다시 막힌다.
     */
    @Test
    @DisplayName("막힌 단계를 되돌리면 그 단계부터 다시 돌아 묶음을 마친다")
    void 막힌_단계를_되돌리면_다시_돌아_묶음을_마친다() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.BLOCKED);
        when(executions.retry(FIRST, DATE)).thenReturn(true);
        when(executions.begin(SECOND, DATE)).thenReturn(StepOutcome.STARTED);

        runner(job(FIRST), job(SECOND)).runBundle(DATE);

        verify(executions).succeed(FIRST, DATE);
        verify(executions).succeed(SECOND, DATE);
        verify(executions).succeed(DailyJobRunner.BUNDLE, DATE);
        verify(executions, never()).fail(eq(DailyJobRunner.BUNDLE), eq(DATE), anyString());
    }

    @Test
    @DisplayName("등록된 단계가 없으면 묶음을 바로 성공으로 닫는다")
    void 단계가_없으면_바로_닫는다() {
        runner().runBundle(DATE);

        verify(executions).succeed(DailyJobRunner.BUNDLE, DATE);
    }

    /*
     * 일시적 오류는 그 단계만 다시 돌린다.
     * 교착이나 락 대기 시간 초과로 그날 집계 전체가 멈추면 사람이 새벽에 깨야 한다.
     */
    @Test
    @DisplayName("일시적 오류는 그 단계만 다시 돌려 성공시킨다")
    void 일시적_오류는_다시_돌린다() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.recordRetry(eq(FIRST), eq(DATE), anyString())).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        when(executions.renew(FIRST, DATE)).thenReturn(true);

        runner(failingTimes(FIRST, 1, new QueryTimeoutException("응답이 없다"))).runBundle(DATE);

        verify(executions).succeed(FIRST, DATE);
        verify(executions).succeed(DailyJobRunner.BUNDLE, DATE);
        verify(executions, never()).fail(eq(FIRST), eq(DATE), anyString());
    }

    /*
     * 상한은 행이 들고 있다. recordRetry 가 0건이면 더 돌리지 않는다.
     * 코드가 세면 이어받은 서버가 상한을 처음부터 다시 쓴다.
     */
    @Test
    @DisplayName("재실행 상한에 닿으면 실패로 확정한다")
    void 상한에_닿으면_실패한다() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.recordRetry(eq(FIRST), eq(DATE), anyString())).thenReturn(false);

        runner(failingTimes(FIRST, 9, new QueryTimeoutException("응답이 없다"))).runBundle(DATE);

        verify(executions).fail(FIRST, DATE, "QueryTimeoutException: 응답이 없다");
        verify(executions).fail(DailyJobRunner.BUNDLE, DATE, FIRST + " 단계가 실패했다");
        verifyNoInteractions(backoff);
    }

    /*
     * 일시적 오류가 아니면 한 번도 다시 돌리지 않는다.
     * 데이터가 어긋난 실패는 몇 번 돌려도 같은 자리에서 터지고, 관리자가 볼 기록만 덮어쓴다.
     */
    @Test
    @DisplayName("일시적 오류가 아니면 바로 실패로 둔다")
    void 일시적_오류가_아니면_바로_실패() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);

        runner(failingTimes(FIRST, 9, new IllegalStateException("원본이 어긋났다"))).runBundle(DATE);

        verify(executions).fail(FIRST, DATE, "IllegalStateException: 원본이 어긋났다");
        verify(executions, never()).recordRetry(eq(FIRST), eq(DATE), anyString());
        verifyNoInteractions(backoff);
    }

    /** 중단 신호를 받으면 더 기다리지 않고 실패로 둔다. 종료 중에 새 작업을 시작하지 않는다. */
    @Test
    @DisplayName("대기 중 중단 신호를 받으면 실패로 둔다")
    void 중단되면_실패한다() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.recordRetry(eq(FIRST), eq(DATE), anyString())).thenReturn(true);
        when(backoff.pause(1)).thenReturn(false);

        runner(failingTimes(FIRST, 9, new QueryTimeoutException("응답이 없다"))).runBundle(DATE);

        verify(executions).fail(FIRST, DATE, "QueryTimeoutException: 응답이 없다");
    }

    /*
     * 기다린 사이에 임대를 잃었으면 다시 돌리지 않는다.
     *
     * 확인하지 않으면 이어받은 서버와 같은 단계를 겹쳐 돌린다. 그리고 남의 행을 닫지도 않는다.
     * 그 판정은 이어받은 쪽이 한다.
     */
    @Test
    @DisplayName("대기 중에 임대를 잃으면 다시 돌리지 않는다")
    void 대기_중_임대를_잃으면_멈춘다() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.recordRetry(eq(FIRST), eq(DATE), anyString())).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        when(executions.renew(FIRST, DATE)).thenReturn(false);

        runner(failingTimes(FIRST, 9, new QueryTimeoutException("응답이 없다"))).runBundle(DATE);

        verify(executions, never()).fail(eq(FIRST), eq(DATE), anyString());
        verify(executions, never()).succeed(DailyJobRunner.BUNDLE, DATE);
    }

    @Test
    @DisplayName("isStep은 등록된 단계 이름만 참이다")
    void isStep은_등록된_단계만_참이다() {
        DailyJobRunner runner = runner(job(FIRST));

        assertThat(runner.isStep(FIRST)).isTrue();
        assertThat(runner.isStep(DailyJobRunner.BUNDLE)).isFalse();
    }

    @Test
    @DisplayName("isRetryable은 묶음이거나 등록된 StandaloneJob일 때만 참이다")
    void isRetryable은_묶음과_등록된_작업만_참이다() {
        DailyJobRunner runner = runnerWithStandalone(standalone("MEMBER_KAKAO_UNLINK_RETRY"));

        assertThat(runner.isRetryable(DailyJobRunner.BUNDLE)).isTrue();
        assertThat(runner.isRetryable("MEMBER_KAKAO_UNLINK_RETRY")).isTrue();
        assertThat(runner.isRetryable("EXPIRE_COUPON:42")).isFalse();
    }

    @Test
    @DisplayName("resume은 묶음 이름이면 runBundle과 같은 경로로 돈다")
    void resume은_묶음이면_runBundle과_같다() {
        stubRenew();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);

        runner(job(FIRST)).resume(DailyJobRunner.BUNDLE, DATE, 1);

        verify(executions).succeed(FIRST, DATE);
        verify(executions).succeed(DailyJobRunner.BUNDLE, DATE);
    }

    /*
     * 묶음 밖 작업도 등록해 두면 resume이 세대와 함께 run을 다시 불러 성공시킨다(#360, #362).
     * attemptNo는 이어받기로 오른 세대이거나 관리자 수동 재실행이 되돌린 세대 그대로 넘겨야,
     * 구현이 결과를 공개할 때 BatchAttemptGuard로 자기 것인지 확인할 수 있다.
     */
    @Test
    @DisplayName("등록된 묶음 밖 작업은 resume이 세대와 함께 run을 다시 불러 성공시킨다")
    void 묶음_밖_작업을_resume으로_다시_돈다() {
        StandaloneJob job = org.mockito.Mockito.mock(StandaloneJob.class);
        when(job.jobName()).thenReturn("MEMBER_KAKAO_UNLINK_RETRY");

        runnerWithStandalone(job).resume("MEMBER_KAKAO_UNLINK_RETRY", DATE, 2);

        verify(job).run(DATE, 2);
        verify(executions).succeed("MEMBER_KAKAO_UNLINK_RETRY", DATE);
    }

    @Test
    @DisplayName("묶음 밖 작업도 일시적 오류면 다시 돌려 성공시킨다")
    void 묶음_밖_작업의_일시적_오류는_다시_돌린다() {
        when(executions.recordRetry(eq("MEMBER_KAKAO_UNLINK_RETRY"), eq(DATE), anyString())).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        when(executions.renew("MEMBER_KAKAO_UNLINK_RETRY", DATE)).thenReturn(true);

        runnerWithStandalone(failingStandaloneTimes(
                "MEMBER_KAKAO_UNLINK_RETRY", 1, new QueryTimeoutException("응답이 없다")))
                .resume("MEMBER_KAKAO_UNLINK_RETRY", DATE, 1);

        verify(executions).succeed("MEMBER_KAKAO_UNLINK_RETRY", DATE);
        verify(executions, never()).fail(eq("MEMBER_KAKAO_UNLINK_RETRY"), eq(DATE), anyString());
    }

    /*
     * 묶음 밖 작업은 번들과 무관하다. 실패해도 DAILY_0000 을 실패시키지 않는다.
     * onFailure 를 번들 단계와 묶음 밖 작업이 다르게 받기 때문이다.
     */
    @Test
    @DisplayName("묶음 밖 작업이 실패해도 묶음은 건드리지 않는다")
    void 묶음_밖_작업이_실패해도_묶음은_그대로다() {
        runnerWithStandalone(failingStandaloneTimes(
                "MEMBER_KAKAO_UNLINK_RETRY", 9, new IllegalStateException("원본이 어긋났다")))
                .resume("MEMBER_KAKAO_UNLINK_RETRY", DATE, 1);

        verify(executions).fail("MEMBER_KAKAO_UNLINK_RETRY", DATE, "IllegalStateException: 원본이 어긋났다");
        verify(executions, never()).fail(eq(DailyJobRunner.BUNDLE), any(LocalDate.class), anyString());
    }
}
