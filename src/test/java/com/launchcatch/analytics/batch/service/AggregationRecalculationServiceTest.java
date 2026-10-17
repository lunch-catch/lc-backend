package com.launchcatch.analytics.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import com.launchcatch.ops.contract.AuditLogWriter;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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

    private final Executor direct = Runnable::run;

    private AggregationRecalculationService service(Executor executor) {
        return new AggregationRecalculationService(
                runner, executionQuery, auditLogWriter, transactionManager, CLOCK, executor);
    }

    private static BatchExecutionRecord run(long runId, LocalDate date, String status) {
        return new BatchExecutionRecord(runId, DailyAggregationRunner.JOB_NAME, date, status,
                LocalDateTime.of(2026, 10, 17, 12, 0), null, null, "api-1", 0, null);
    }

    private void stubOldestRetained() {
        when(runner.oldestRetained(TODAY)).thenReturn(OLDEST);
    }

    @Test
    void 날짜를_잡으면_감사_로그를_남기고_집계를_시작시키고_실행_행_ID를_돌려준다() {
        stubOldestRetained();
        List<BatchExecutionRecord> before = List.of(run(301L, YESTERDAY, "SUCCESS"));
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(before);
        when(runner.acquire(YESTERDAY, before)).thenReturn(true);

        long runId = service(direct).request(ADMIN_ID, YESTERDAY);

        assertThat(runId).isEqualTo(301L);
        verify(auditLogWriter).write(ADMIN_ID, "ANALYTICS_RECALCULATE", "2026-10-16", "DAILY_AGGREGATION");
        verify(runner).aggregate(YESTERDAY);
    }

    @Test
    void 오늘은_집계할_수_없다() {
        assertThatThrownBy(() -> service(direct).request(ADMIN_ID, TODAY))
                .isInstanceOfSatisfying(AnalyticsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.UNAGGREGATABLE_DATE));
        verify(runner, never()).acquire(any(), any());
    }

    @Test
    void 미래_날짜는_집계할_수_없다() {
        assertThatThrownBy(() -> service(direct).request(ADMIN_ID, TODAY.plusDays(1)))
                .isInstanceOfSatisfying(AnalyticsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.UNAGGREGATABLE_DATE));
    }

    @Test
    void 보존_기간보다_하루_오래된_날짜는_집계할_수_없다() {
        stubOldestRetained();

        assertThatThrownBy(() -> service(direct).request(ADMIN_ID, OLDEST.minusDays(1)))
                .isInstanceOfSatisfying(AnalyticsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.UNAGGREGATABLE_DATE));
        verify(runner, never()).acquire(any(), any());
    }

    @Test
    void 보존_기간의_첫날은_집계할_수_있다() {
        stubOldestRetained();
        List<BatchExecutionRecord> before = List.of(run(5L, OLDEST, "FAILED"));
        when(executionQuery.findByBusinessDate(OLDEST)).thenReturn(before);
        when(runner.acquire(OLDEST, before)).thenReturn(true);

        assertThat(service(direct).request(ADMIN_ID, OLDEST)).isEqualTo(5L);
    }

    @Test
    void 날짜가_없으면_집계할_수_없다() {
        assertThatThrownBy(() -> service(direct).request(ADMIN_ID, null))
                .isInstanceOfSatisfying(AnalyticsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.UNAGGREGATABLE_DATE));
    }

    @Test
    void 이미_실행_중이면_충돌이고_집계도_감사_로그도_없다() {
        stubOldestRetained();
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(List.of(run(301L, YESTERDAY, "RUNNING")));
        when(runner.acquire(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service(direct).request(ADMIN_ID, YESTERDAY))
                .isInstanceOfSatisfying(AnalyticsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AnalyticsErrorCode.AGGREGATION_ALREADY_RUNNING));
        verify(runner, never()).aggregate(any());
        verify(auditLogWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    void 실행기가_가득_차_집계를_시작하지_못하면_잡은_실행을_실패로_닫고_예외를_올린다() {
        stubOldestRetained();
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(List.of(run(301L, YESTERDAY, "RUNNING")));
        when(runner.acquire(any(), any())).thenReturn(true);
        Executor full = task -> {
            throw new RejectedExecutionException("가득 참");
        };

        assertThatThrownBy(() -> service(full).request(ADMIN_ID, YESTERDAY))
                .isInstanceOf(RejectedExecutionException.class);
        verify(runner).abandon(any(LocalDate.class), any(String.class));
    }

    @Test
    void 감사_로그를_남기지_못하면_집계를_시작하지_않고_잡은_실행을_실패로_닫고_예외를_올린다() {
        stubOldestRetained();
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(List.of(run(301L, YESTERDAY, "RUNNING")));
        when(runner.acquire(any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("감사 로그 실패")).when(auditLogWriter)
                .write(any(), any(), any(), any());

        assertThatThrownBy(() -> service(direct).request(ADMIN_ID, YESTERDAY))
                .isInstanceOf(IllegalStateException.class);
        verify(runner).abandon(any(LocalDate.class), any(String.class));
        verify(runner, never()).aggregate(any());
    }

    @Test
    void 서비스가_직접_만든_실행기에서_다른_스레드로_집계를_돌린다() {
        stubOldestRetained();
        List<BatchExecutionRecord> before = List.of(run(301L, YESTERDAY, "SUCCESS"));
        when(executionQuery.findByBusinessDate(YESTERDAY)).thenReturn(before);
        when(runner.acquire(YESTERDAY, before)).thenReturn(true);
        AggregationRecalculationService owned = new AggregationRecalculationService(
                runner, executionQuery, auditLogWriter, transactionManager, CLOCK);

        long runId = owned.request(ADMIN_ID, YESTERDAY);
        owned.shutdown();

        assertThat(runId).isEqualTo(301L);
        verify(runner, timeout(5000)).aggregate(YESTERDAY);
    }

    @Test
    void 실행기를_받은_생성자로_만든_서비스는_내릴_실행기가_없어도_내려진다() {
        assertThatCode(() -> service(direct).shutdown()).doesNotThrowAnyException();
    }
}
