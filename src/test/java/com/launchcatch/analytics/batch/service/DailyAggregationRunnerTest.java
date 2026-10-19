package com.launchcatch.analytics.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.batch.AggregationOutcome;
import com.launchcatch.analytics.batch.PendingReason;
import com.launchcatch.analytics.batch.repository.PendingDateRepository;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import com.launchcatch.ops.contract.BatchRerunner;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.service.BatchExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class DailyAggregationRunnerTest {

    private static final String JOB = DailyAggregationRunner.JOB_NAME;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate TWO_DAYS_AGO = LocalDate.of(2026, 10, 15);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T16:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private ObjectProvider<AggregationStep<?>> stepProvider;
    @Mock
    private BatchExecutionService executions;
    @Mock
    private BatchExecutionQueryService executionQuery;
    @Mock
    private BatchRerunner rerunner;
    @Mock
    private PendingDateRepository pending;
    @Mock
    private RetryBackoff backoff;
    @Mock
    private PlatformTransactionManager transactionManager;

    private SimpleMeterRegistry registry;
    private final List<AggregationStep<?>> steps = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private DailyAggregationRunner runner() {
        when(stepProvider.orderedStream()).thenAnswer(invocation -> steps.stream());
        return new DailyAggregationRunner(stepProvider, executions, executionQuery, rerunner, pending,
                new AggregationMetrics(registry), backoff, transactionManager, CLOCK);
    }

    private static BatchExecutionRecord execution(String jobName, LocalDate date, String status) {
        return new BatchExecutionRecord(1L, jobName, date, status, LocalDateTime.now(CLOCK), null, null,
                "server-1", 0, null, 1);
    }

    private static BatchExecutionRecord sourceDone(LocalDate date) {
        return execution(DailyAggregationRunner.SOURCE_JOB_NAME, date, "SUCCESS");
    }

    private void aggregatableSetUp() {
        when(executions.renew(JOB, YESTERDAY)).thenReturn(true);
    }

    // --- 날짜 목록 ---

    @Test
    void 대상_날짜는_전일_전전일_미처리_날짜를_오래된_순서로_중복_없이_돌려준다() {
        when(pending.dates()).thenReturn(List.of(LocalDate.of(2026, 10, 1), TWO_DAYS_AGO));

        List<LocalDate> dates = runner().targetDates(TODAY);

        assertThat(dates).containsExactly(LocalDate.of(2026, 10, 1), TWO_DAYS_AGO, YESTERDAY);
    }

    @Test
    void 보존_기간_밖_날짜는_빼고_미처리_목록에서도_지운다() {
        LocalDate tooOld = TODAY.minusMonths(13).minusDays(1);
        LocalDate oldest = TODAY.minusMonths(13);
        when(pending.dates()).thenReturn(List.of(tooOld, oldest));

        List<LocalDate> dates = runner().targetDates(TODAY);

        verify(pending).removeBefore(oldest);
        assertThat(dates).contains(oldest).doesNotContain(tooOld);
    }

    @Test
    void 오늘과_미래_날짜는_대상이_아니다() {
        when(pending.dates()).thenReturn(List.of(TODAY, TODAY.plusDays(1)));

        List<LocalDate> dates = runner().targetDates(TODAY);

        assertThat(dates).containsExactly(TWO_DAYS_AGO, YESTERDAY);
    }

    @Test
    void 가장_오래된_집계_가능_날짜는_열세_달_전_같은_날이다() {
        assertThat(runner().oldestRetained(TODAY)).isEqualTo(LocalDate.of(2025, 9, 17));
    }

    // --- 원천 확정과 날짜 잡기 ---

    @Test
    void 정산_해제와_대사가_끝나지_않았으면_건너뛰고_미처리로_남긴다() {
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(List.of());

        AggregationOutcome outcome = runner().runDate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.SOURCE_NOT_READY);
        verify(pending).record(eq(YESTERDAY), eq(PendingReason.SOURCE_NOT_READY), any(LocalDateTime.class));
        verify(executions, never()).claim(any(), any());
    }

    @Test
    void 정산_해제와_대사가_실패했으면_확정으로_보지_않는다() {
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(
                List.of(execution(DailyAggregationRunner.SOURCE_JOB_NAME, YESTERDAY, "FAILED")));

        assertThat(runner().runDate(YESTERDAY)).isEqualTo(AggregationOutcome.SOURCE_NOT_READY);
    }

    @Test
    void 행이_없으면_claim으로_잡는다() {
        when(executions.claim(JOB, YESTERDAY)).thenReturn(true);

        assertThat(runner().acquire(YESTERDAY, List.of())).isTrue();
    }

    @Test
    void 실패한_행은_retry로_잡는다() {
        when(executions.retry(JOB, YESTERDAY)).thenReturn(true);

        assertThat(runner().acquire(YESTERDAY, List.of(execution(JOB, YESTERDAY, "FAILED")))).isTrue();
    }

    @Test
    void 성공한_행은_rerun으로_잡는다() {
        when(rerunner.rerun(JOB, YESTERDAY)).thenReturn(true);

        assertThat(runner().acquire(YESTERDAY, List.of(execution(JOB, YESTERDAY, "SUCCESS")))).isTrue();
    }

    @Test
    void 실행_중인_행은_건너뛴다() {
        assertThat(runner().acquire(YESTERDAY, List.of(execution(JOB, YESTERDAY, "RUNNING")))).isFalse();
        verify(executions, never()).claim(any(), any());
        verify(executions, never()).retry(any(), any());
        verify(rerunner, never()).rerun(any(), any());
    }

    @Test
    void 다른_작업의_행은_내_행으로_보지_않는다() {
        when(executions.claim(JOB, YESTERDAY)).thenReturn(true);

        assertThat(runner().acquire(YESTERDAY,
                List.of(execution("BILLING_DAILY_RELEASE", YESTERDAY, "RUNNING")))).isTrue();
    }

    @Test
    void 잡지_못하면_집계하지_않고_건너뛴다() {
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(
                List.of(sourceDone(YESTERDAY), execution(JOB, YESTERDAY, "RUNNING")));

        AggregationOutcome outcome = runner().runDate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.BUSY);
        verify(executions, never()).succeed(any(), any(), anyInt());
        verify(pending, never()).record(any(), any(), any());
    }

    // --- 집계 ---

    @Test
    void 단계가_없어도_공개까지_마치고_성공으로_닫고_미처리에서_지운다() {
        aggregatableSetUp();

        AggregationOutcome outcome = runner().aggregate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.SUCCEEDED);
        verify(executions).succeed(JOB, YESTERDAY, 0);
        verify(pending).remove(YESTERDAY);
        verify(transactionManager).commit(any());
    }

    @Test
    void 단계는_모으기를_모두_마친_뒤_한_트랜잭션에서_순서대로_공개한다() {
        aggregatableSetUp();
        steps.add(new RecordingStep("FirstStep", 3, calls));
        steps.add(new RecordingStep("SecondStep", 4, calls));

        AggregationOutcome outcome = runner().aggregate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.SUCCEEDED);
        assertThat(calls).containsExactly(
                "FirstStep:collect", "SecondStep:collect", "FirstStep:publish", "SecondStep:publish");
        verify(executions).succeed(JOB, YESTERDAY, 7);
        verify(transactionManager).commit(any());
        assertThat(registry.get("analytics_aggregation_rows").tag("step", "FirstStep").gauge().value())
                .isEqualTo(3.0);
        assertThat(registry.get("analytics_aggregation_step_seconds").tag("step", "SecondStep").timer()
                .count()).isEqualTo(1L);
    }

    @Test
    void 일시적이_아닌_오류는_재시도_없이_실패로_닫고_미처리로_남긴다() {
        aggregatableSetUp();
        steps.add(new FailingStep(new IllegalStateException("원천 데이터가 어긋났다")));

        AggregationOutcome outcome = runner().aggregate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.FAILED);
        verify(executions).fail(eq(JOB), eq(YESTERDAY), any(String.class));
        verify(executions, never()).recordRetry(any(), any(), any());
        verify(pending).record(eq(YESTERDAY), eq(PendingReason.FAILED), any(LocalDateTime.class));
        verify(executions, never()).succeed(any(), any(), anyInt());
        assertThat(registry.get("analytics_aggregation_total").tag("result", "failed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void 공개_도중_오류는_트랜잭션을_롤백하고_실패로_닫는다() {
        aggregatableSetUp();
        steps.add(new PublishFailingStep());

        AggregationOutcome outcome = runner().aggregate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.FAILED);
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void 일시적_오류는_트랜잭션_밖에서_기다린_뒤_모으기부터_다시_하고_성공하면_닫는다() {
        aggregatableSetUp();
        when(executions.recordRetry(eq(JOB), eq(YESTERDAY), any(String.class))).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        FlakyStep flaky = new FlakyStep(1);
        steps.add(flaky);

        AggregationOutcome outcome = runner().aggregate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.SUCCEEDED);
        assertThat(flaky.collectCalls).isEqualTo(2);
        InOrder order = inOrder(backoff, transactionManager);
        order.verify(backoff).pause(1);
        order.verify(transactionManager).commit(any());
        verify(executions, never()).fail(any(), any(), any());
    }

    @Test
    void 재실행_상한에_닿으면_일시적_오류여도_실패로_닫는다() {
        aggregatableSetUp();
        when(executions.recordRetry(eq(JOB), eq(YESTERDAY), any(String.class))).thenReturn(false);
        steps.add(new FlakyStep(5));

        AggregationOutcome outcome = runner().aggregate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.FAILED);
        verify(backoff, never()).pause(anyInt());
        verify(executions).fail(eq(JOB), eq(YESTERDAY), any(String.class));
    }

    @Test
    void 기다림이_중단되면_더_돌리지_않고_실패로_닫는다() {
        aggregatableSetUp();
        when(executions.recordRetry(eq(JOB), eq(YESTERDAY), any(String.class))).thenReturn(true);
        when(backoff.pause(1)).thenReturn(false);
        steps.add(new FlakyStep(5));

        assertThat(runner().aggregate(YESTERDAY)).isEqualTo(AggregationOutcome.FAILED);
    }

    @Test
    void 기다린_사이에_실행_행을_잃으면_다시_돌지_않고_결과를_판정하지_않는다() {
        when(executions.renew(JOB, YESTERDAY)).thenReturn(true, false);
        when(executions.recordRetry(eq(JOB), eq(YESTERDAY), any(String.class))).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        FlakyStep flaky = new FlakyStep(5);
        steps.add(flaky);

        AggregationOutcome outcome = runner().aggregate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.LEASE_LOST);
        assertThat(flaky.collectCalls).isEqualTo(1);
        verify(executions, never()).fail(any(), any(), any());
        verify(pending, never()).record(any(), any(), any());
    }

    @Test
    void 단계를_시작하기_전에_실행_행을_잃었으면_모으지_않고_멈춘다() {
        when(executions.renew(JOB, YESTERDAY)).thenReturn(false);
        steps.add(new RecordingStep("FirstStep", 1, calls));

        AggregationOutcome outcome = runner().aggregate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.LEASE_LOST);
        assertThat(calls).isEmpty();
        verify(executions, never()).succeed(any(), any(), anyInt());
    }

    @Test
    void 공개_직전에_실행_행을_잃었으면_공개하지_않는다() {
        when(executions.renew(JOB, YESTERDAY)).thenReturn(true, false);
        steps.add(new RecordingStep("FirstStep", 1, calls));

        AggregationOutcome outcome = runner().aggregate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.LEASE_LOST);
        assertThat(calls).containsExactly("FirstStep:collect");
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void 실패를_기록하다_또_실패해도_원래_오류를_숨기지_않고_미처리로_남긴다() {
        aggregatableSetUp();
        doThrow(new IllegalStateException("기록 실패")).when(executions).fail(any(), any(), any());
        steps.add(new FailingStep(new IllegalArgumentException("원인")));

        AggregationOutcome outcome = runner().aggregate(YESTERDAY);

        assertThat(outcome).isEqualTo(AggregationOutcome.FAILED);
        verify(pending).record(eq(YESTERDAY), eq(PendingReason.FAILED), any(LocalDateTime.class));
    }

    @Test
    void 날짜를_잡고_시작하지_못한_실행은_실패로_닫고_미처리로_남긴다() {
        runner().abandon(YESTERDAY, "시작하지 못했다");

        verify(executions).fail(JOB, YESTERDAY, "시작하지 못했다");
        verify(pending).record(eq(YESTERDAY), eq(PendingReason.FAILED), any(LocalDateTime.class));
    }

    // --- 전체 실행 ---

    @Test
    void 전체_실행은_날짜마다_따로_돌고_한_날짜의_예상하지_못한_오류가_다른_날짜를_막지_않는다() {
        when(pending.dates()).thenReturn(List.of());
        when(pending.count()).thenReturn(2L);
        when(executionQuery.findByBusinessDate(TWO_DAYS_AGO)).thenThrow(new IllegalStateException("조회 실패"));
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(
                List.of(sourceDone(YESTERDAY)));
        when(executions.claim(JOB, YESTERDAY)).thenReturn(true);
        aggregatableSetUp();

        Map<LocalDate, AggregationOutcome> outcomes = runner().runAll();

        assertThat(outcomes).containsExactly(
                Map.entry(TWO_DAYS_AGO, AggregationOutcome.FAILED),
                Map.entry(YESTERDAY, AggregationOutcome.SUCCEEDED));
        verify(pending).record(eq(TWO_DAYS_AGO), eq(PendingReason.FAILED), any(LocalDateTime.class));
        assertThat(registry.get("analytics_aggregation_pending_dates").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void 전체_실행의_대상은_전일_전전일과_미처리_날짜다() {
        when(pending.dates()).thenReturn(List.of(LocalDate.of(2026, 10, 10)));
        when(executionQuery.findByBusinessDate(any())).thenReturn(List.of());

        Map<LocalDate, AggregationOutcome> outcomes = runner().runAll();

        assertThat(outcomes.keySet()).containsExactly(LocalDate.of(2026, 10, 10), TWO_DAYS_AGO, YESTERDAY);
        assertThat(outcomes.values()).containsOnly(AggregationOutcome.SOURCE_NOT_READY);
    }

    // --- 가짜 단계 ---

    private static final class RecordingStep implements AggregationStep<String> {

        private final String name;
        private final int rows;
        private final List<String> calls;

        RecordingStep(String name, int rows, List<String> calls) {
            this.name = name;
            this.rows = rows;
            this.calls = calls;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String collect(LocalDate businessDate) {
            calls.add(name + ":collect");
            return name;
        }

        @Override
        public int publish(LocalDate businessDate, String collected) {
            calls.add(name + ":publish");
            return rows;
        }
    }

    private static final class FailingStep implements AggregationStep<Void> {

        private final RuntimeException failure;

        FailingStep(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public String name() {
            return "FailingStep";
        }

        @Override
        public Void collect(LocalDate businessDate) {
            throw failure;
        }

        @Override
        public int publish(LocalDate businessDate, Void collected) {
            return 0;
        }
    }

    private static final class PublishFailingStep implements AggregationStep<Void> {

        @Override
        public String name() {
            return "PublishFailingStep";
        }

        @Override
        public Void collect(LocalDate businessDate) {
            return null;
        }

        @Override
        public int publish(LocalDate businessDate, Void collected) {
            throw new IllegalStateException("교체 실패");
        }
    }

    private static final class FlakyStep implements AggregationStep<Void> {

        private final int failures;
        int collectCalls;

        FlakyStep(int failures) {
            this.failures = failures;
        }

        @Override
        public String name() {
            return "FlakyStep";
        }

        @Override
        public Void collect(LocalDate businessDate) {
            collectCalls++;
            if (collectCalls <= failures) {
                throw new QueryTimeoutException("응답 시간 초과");
            }
            return null;
        }

        @Override
        public int publish(LocalDate businessDate, Void collected) {
            return 1;
        }
    }
}
