package com.launchcatch.ops.job;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.scheduler.DailyJob;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.service.BatchExecutionService;
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
 * 묶음이 순서를 지키고, 멈춰야 할 때 멈추는지 본다.
 *
 * 여기서 막는 사고는 둘이다. 앞 단계가 실패했는데 뒤 단계가 도는 것, 그리고 점유를 잃은 서버가
 * 남은 단계를 계속 도는 것이다. 둘 다 결과가 두 번 반영되거나 어긋난 상태로 남는다.
 */
@ExtendWith(MockitoExtension.class)
class DailyJobSchedulerTest {

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

    private DailyJobScheduler scheduler(DailyJob... jobs) {
        return new DailyJobScheduler(List.of(jobs), executions, backoff);
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

    private void claimBundle() {
        when(executions.businessDate()).thenReturn(DATE);
        when(executions.claim(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.renew(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
    }

    @Test
    @DisplayName("다른 서버가 묶음을 점유했으면 아무 단계도 돌지 않는다")
    void 점유에_실패하면_돌지_않는다() {
        when(executions.businessDate()).thenReturn(DATE);
        when(executions.claim(DailyJobScheduler.BUNDLE, DATE)).thenReturn(false);

        scheduler(job(FIRST)).runDailyBundle();

        verify(executions, never()).begin(anyString(), eq(DATE));
    }

    @Test
    @DisplayName("점유하면 단계를 순서대로 돌고 묶음을 성공으로 닫는다")
    void 순서대로_돌고_닫는다() {
        when(executions.businessDate()).thenReturn(DATE);
        when(executions.claim(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.renew(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.begin(SECOND, DATE)).thenReturn(StepOutcome.STARTED);

        scheduler(job(FIRST), job(SECOND)).runDailyBundle();

        InOrder order = inOrder(executions);
        order.verify(executions).succeed(FIRST, DATE);
        order.verify(executions).succeed(SECOND, DATE);
        order.verify(executions).succeed(DailyJobScheduler.BUNDLE, DATE);
    }

    /*
     * 앞 단계가 실패하면 뒤 단계는 돌지 않는다 (규칙 6).
     * 00:00 묶음은 뒤 단계가 앞 단계의 결과를 읽으므로, 그대로 진행하면 빈 입력으로 집계한다.
     */
    @Test
    @DisplayName("앞 단계가 실패하면 뒤 단계를 돌리지 않고 묶음도 실패한다")
    void 앞_단계가_실패하면_멈춘다() {
        when(executions.businessDate()).thenReturn(DATE);
        when(executions.claim(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.renew(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);

        scheduler(failing(FIRST), job(SECOND)).runDailyBundle();

        verify(executions).fail(FIRST, DATE, "IllegalStateException: 원본을 읽지 못했다");
        verify(executions).fail(DailyJobScheduler.BUNDLE, DATE, FIRST + " 단계가 실패했다");
        verify(executions, never()).begin(SECOND, DATE);
    }

    /*
     * 점유를 잃으면 멈춘다.
     * 이어받은 서버가 이미 같은 단계를 돌고 있어서, 여기서 계속 가면 둘이 겹친다.
     */
    @Test
    @DisplayName("묶음 점유를 잃으면 단계를 시작하지 않는다")
    void 점유를_잃으면_멈춘다() {
        when(executions.businessDate()).thenReturn(DATE);
        when(executions.claim(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.renew(DailyJobScheduler.BUNDLE, DATE)).thenReturn(false);

        scheduler(job(FIRST)).runDailyBundle();

        verify(executions, never()).begin(FIRST, DATE);
        verify(executions, never()).succeed(DailyJobScheduler.BUNDLE, DATE);
    }

    @Test
    @DisplayName("이미 끝난 단계는 건너뛰고 다음으로 간다")
    void 끝난_단계는_건너뛴다() {
        when(executions.businessDate()).thenReturn(DATE);
        when(executions.claim(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.renew(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.ALREADY_DONE);
        when(executions.begin(SECOND, DATE)).thenReturn(StepOutcome.STARTED);

        scheduler(job(FIRST), job(SECOND)).runDailyBundle();

        verify(executions, never()).succeed(FIRST, DATE);
        verify(executions).succeed(SECOND, DATE);
    }

    @Test
    @DisplayName("막힌 단계를 만나면 묶음을 실패로 닫는다")
    void 막힌_단계면_묶음을_닫는다() {
        when(executions.businessDate()).thenReturn(DATE);
        when(executions.claim(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.renew(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.BLOCKED);

        scheduler(job(FIRST), job(SECOND)).runDailyBundle();

        verify(executions).fail(DailyJobScheduler.BUNDLE, DATE, FIRST + " 단계를 시작할 수 없다");
        verify(executions, never()).begin(SECOND, DATE);
    }

    @Test
    @DisplayName("생존 신호는 내가 들고 있는 행을 갱신한다")
    void 생존_신호() {
        scheduler(job(FIRST)).sendHeartbeat();

        verify(executions).renewOwned();
    }

    @Test
    @DisplayName("묶음을 이어받으면 그 영업일로 다시 돈다")
    void 묶음을_이어받아_다시_돈다() {
        BatchExecutionLog bundle = BatchExecutionLog.running(DailyJobScheduler.BUNDLE, DATE, "batch-2");
        when(executions.takeOverStale()).thenReturn(List.of(bundle));
        when(executions.renew(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);

        scheduler(job(FIRST)).takeOverStale();

        verify(executions).succeed(DailyJobScheduler.BUNDLE, DATE);
    }

    /*
     * 묶음의 단계는 따로 손대지 않는다.
     * 묶음을 다시 도는 길에서 begin 이 그 행을 다시 가져간다. 여기서 실패로 닫으면 그 길이 막힌다.
     */
    @Test
    @DisplayName("이어받은 행이 묶음의 단계면 따로 닫지 않는다")
    void 단계는_따로_닫지_않는다() {
        BatchExecutionLog step = BatchExecutionLog.running(FIRST, DATE, "batch-2");
        when(executions.takeOverStale()).thenReturn(List.of(step));

        scheduler(job(FIRST)).takeOverStale();

        verify(executions, never()).fail(eq(FIRST), eq(DATE), anyString());
    }

    /*
     * 묶음도 아니고 등록된 단계도 아닌 작업은 다시 돌릴 길이 없다.
     * 소유자만 바꿔 두면 내 생존 신호가 그 행을 영원히 살아 있게 만들어 아무도 멈춘 것을 모른다.
     */
    @Test
    @DisplayName("다시 돌릴 경로가 없는 작업은 실패로 닫는다")
    void 재개_경로가_없으면_닫는다() {
        BatchExecutionLog orphan = BatchExecutionLog.running("EXPIRE_COUPON:42", DATE, "batch-2");
        when(executions.takeOverStale()).thenReturn(List.of(orphan));

        scheduler(job(FIRST)).takeOverStale();

        verify(executions).fail("EXPIRE_COUPON:42", DATE, "멈춘 실행을 이어받았으나 다시 돌릴 경로가 없다");
    }

    @Test
    @DisplayName("이어받을 것이 없으면 아무것도 하지 않는다")
    void 이어받을_것이_없다() {
        when(executions.takeOverStale()).thenReturn(List.of());

        scheduler(job(FIRST)).takeOverStale();

        verify(executions).takeOverStale();
    }

    /*
     * 일시적 오류는 그 단계만 다시 돌린다.
     * 교착이나 락 대기 시간 초과로 그날 집계 전체가 멈추면 사람이 새벽에 깨야 한다.
     */
    @Test
    @DisplayName("일시적 오류는 그 단계만 다시 돌려 성공시킨다")
    void 일시적_오류는_다시_돌린다() {
        claimBundle();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.recordRetry(eq(FIRST), eq(DATE), anyString())).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);

        scheduler(failingTimes(FIRST, 1, new QueryTimeoutException("응답이 없다"))).runDailyBundle();

        verify(executions).succeed(FIRST, DATE);
        verify(executions).succeed(DailyJobScheduler.BUNDLE, DATE);
        verify(executions, never()).fail(eq(FIRST), eq(DATE), anyString());
    }

    /*
     * 상한은 행이 들고 있다. recordRetry 가 0건이면 더 돌리지 않는다.
     * 코드가 세면 이어받은 서버가 상한을 처음부터 다시 쓴다.
     */
    @Test
    @DisplayName("재실행 상한에 닿으면 실패로 확정한다")
    void 상한에_닿으면_실패한다() {
        claimBundle();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.recordRetry(eq(FIRST), eq(DATE), anyString())).thenReturn(false);

        scheduler(failingTimes(FIRST, 9, new QueryTimeoutException("응답이 없다"))).runDailyBundle();

        verify(executions).fail(FIRST, DATE, "QueryTimeoutException: 응답이 없다");
        verify(executions).fail(DailyJobScheduler.BUNDLE, DATE, FIRST + " 단계가 실패했다");
        verifyNoInteractions(backoff);
    }

    /*
     * 일시적 오류가 아니면 한 번도 다시 돌리지 않는다.
     * 데이터가 어긋난 실패는 몇 번 돌려도 같은 자리에서 터지고, 관리자가 볼 기록만 덮어쓴다.
     */
    @Test
    @DisplayName("일시적 오류가 아니면 바로 실패로 둔다")
    void 일시적_오류가_아니면_바로_실패() {
        claimBundle();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);

        scheduler(failingTimes(FIRST, 9, new IllegalStateException("원본이 어긋났다"))).runDailyBundle();

        verify(executions).fail(FIRST, DATE, "IllegalStateException: 원본이 어긋났다");
        verify(executions, never()).recordRetry(eq(FIRST), eq(DATE), anyString());
        verifyNoInteractions(backoff);
    }

    /** 중단 신호를 받으면 더 기다리지 않고 실패로 둔다. 종료 중에 새 작업을 시작하지 않는다. */
    @Test
    @DisplayName("대기 중 중단 신호를 받으면 실패로 둔다")
    void 중단되면_실패한다() {
        claimBundle();
        when(executions.begin(FIRST, DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.recordRetry(eq(FIRST), eq(DATE), anyString())).thenReturn(true);
        when(backoff.pause(1)).thenReturn(false);

        scheduler(failingTimes(FIRST, 9, new QueryTimeoutException("응답이 없다"))).runDailyBundle();

        verify(executions).fail(FIRST, DATE, "QueryTimeoutException: 응답이 없다");
    }

    @Test
    @DisplayName("등록된 단계가 없으면 묶음을 바로 성공으로 닫는다")
    void 단계가_없으면_바로_닫는다() {
        when(executions.businessDate()).thenReturn(DATE);
        when(executions.claim(DailyJobScheduler.BUNDLE, DATE)).thenReturn(true);

        scheduler().runDailyBundle();

        verify(executions).succeed(DailyJobScheduler.BUNDLE, DATE);
    }
}
