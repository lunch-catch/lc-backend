package com.lunchcatch.analytics.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.DailyAggregation;
import com.lunchcatch.analytics.exception.AnalyticsErrorCode;
import com.lunchcatch.analytics.exception.AnalyticsException;
import com.lunchcatch.ops.contract.AuditLogWriter;
import com.lunchcatch.ops.contract.BatchExecutionQueryService;
import com.lunchcatch.ops.contract.BatchExecutionRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class AggregationRecalculationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"),
            ZoneId.of("Asia/Seoul"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate OLDEST = LocalDate.of(2025, 9, 17);
    private static final long ADMIN_ID = 9L;

    @Mock
    private DailyAggregationRunner runner;
    @Mock
    private BatchExecutionQueryService executionQuery;
    @Mock
    private AuditLogWriter auditLogWriter;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AggregationRecalculationService service() {
        return new AggregationRecalculationService(runner, executionQuery, auditLogWriter, transactionManager, CLOCK);
    }

    private static BatchExecutionRecord run(long runId, String jobName, LocalDate date, String status) {
        return new BatchExecutionRecord(runId, jobName, date, status,
                LocalDateTime.of(2026, 10, 17, 12, 0), null, null, "RETRY_REQUESTED", 0, null, 2);
    }

    private static BatchExecutionRecord run(long runId, LocalDate date, String status) {
        return run(runId, DailyAggregation.JOB_NAME, date, status);
    }

    private void stubOldestRetained() {
        when(runner.oldestRetained(TODAY)).thenReturn(OLDEST);
    }

    @Test
    void 날짜를_배치_서버에_넘기면_감사_로그를_남기고_실행_행_ID를_돌려주고_여기서는_집계하지_않는다() {
        stubOldestRetained();
        List<BatchExecutionRecord> before = List.of(run(301L, YESTERDAY, "SUCCESS"));
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(before, List.of(run(301L, YESTERDAY, "RUNNING")));
        when(runner.request(YESTERDAY, before)).thenReturn(true);

        long runId = service().request(ADMIN_ID, YESTERDAY);

        assertThat(runId).isEqualTo(301L);
        verify(auditLogWriter).write(ADMIN_ID, "ANALYTICS_RECALCULATE", "2026-10-16", "DAILY_AGGREGATION");
        verify(transactionManager).commit(any());
        verify(runner, never()).aggregate(any(), anyInt());
        verify(runner, never()).acquire(any(), any());
    }

    @Test
    void 실행_행이_없던_날짜는_새로_만든_행의_ID를_돌려준다() {
        stubOldestRetained();
        BatchExecutionRecord source = run(5L, "BILLING_DAILY_RELEASE", YESTERDAY, "SUCCESS");
        when(executionQuery.findByBusinessDate(YESTERDAY))
                .thenReturn(List.of(source), List.of(source, run(7L, YESTERDAY, "RUNNING")));
        when(runner.request(YESTERDAY, List.of(source))).thenReturn(true);

        assertThat(service().request(ADMIN_ID, YESTERDAY)).isEqualTo(7L);
    }

    @Test
    void 오늘은_집계할_수_없다() {
        assertThatThrownBy(() -> service().request(ADMIN_ID, TODAY))
                .isInstanceOfSatisfying(AnalyticsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.UNAGGREGATABLE_DATE));
        verify(runner, never()).request(any(), any());
    }

    @Test
    void 미래_날짜는_집계할_수_없다() {
        assertThatThrownBy(() -> service().request(ADMIN_ID, TODAY.plusDays(1)))
                .isInstanceOfSatisfying(AnalyticsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.UNAGGREGATABLE_DATE));
    }

    @Test
    void 보존_기간보다_하루_오래된_날짜는_집계할_수_없다() {
        stubOldestRetained();

        assertThatThrownBy(() -> service().request(ADMIN_ID, OLDEST.minusDays(1)))
                .isInstanceOfSatisfying(AnalyticsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.UNAGGREGATABLE_DATE));
        verify(runner, never()).request(any(), any());
    }

    @Test
    void 보존_기간의_첫날은_집계할_수_있다() {
        stubOldestRetained();
        List<BatchExecutionRecord> before = List.of(run(5L, OLDEST, "FAILED"));
        when(executionQuery.findByBusinessDate(OLDEST)).thenReturn(before, List.of(run(5L, OLDEST, "RUNNING")));
        when(runner.request(OLDEST, before)).thenReturn(true);

        assertThat(service().request(ADMIN_ID, OLDEST)).isEqualTo(5L);
    }

    @Test
    void 날짜가_없으면_집계할_수_없다() {
        assertThatThrownBy(() -> service().request(ADMIN_ID, null))
                .isInstanceOfSatisfying(AnalyticsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.UNAGGREGATABLE_DATE));
    }

    @Test
    void 이미_실행_중이면_충돌이고_감사_로그를_남기지_않고_트랜잭션을_되돌린다() {
        stubOldestRetained();
        List<BatchExecutionRecord> before = List.of(run(301L, YESTERDAY, "RUNNING"));
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(before);
        when(runner.request(YESTERDAY, before)).thenReturn(false);

        assertThatThrownBy(() -> service().request(ADMIN_ID, YESTERDAY))
                .isInstanceOfSatisfying(AnalyticsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.AGGREGATION_ALREADY_RUNNING));
        verify(auditLogWriter, never()).write(any(), any(), any(), any());
        verify(transactionManager).rollback(any());
    }

    // 요청 표식과 감사 로그가 한 트랜잭션이라 감사 로그를 남기지 못하면 배치 서버가 집을 행도 커밋되지 않는다
    @Test
    void 감사_로그를_남기지_못하면_넘긴_요청을_되돌리고_예외를_올린다() {
        stubOldestRetained();
        List<BatchExecutionRecord> before = List.of(run(301L, YESTERDAY, "SUCCESS"));
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(before);
        when(runner.request(YESTERDAY, before)).thenReturn(true);
        doThrow(new IllegalStateException("감사 로그 실패")).when(auditLogWriter)
                .write(any(), any(), any(), any());

        assertThatThrownBy(() -> service().request(ADMIN_ID, YESTERDAY))
                .isInstanceOf(IllegalStateException.class);
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }
}
