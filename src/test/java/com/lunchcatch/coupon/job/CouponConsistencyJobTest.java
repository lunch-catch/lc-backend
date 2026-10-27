package com.lunchcatch.coupon.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.coupon.CouponTestProperties;
import com.lunchcatch.coupon.service.CouponConsistencyService;
import com.lunchcatch.coupon.service.CouponBatchExecutionService;
import com.lunchcatch.ops.contract.BatchExecutionQueryService;
import com.lunchcatch.ops.contract.BatchExecutionRecord;
import com.lunchcatch.ops.scheduler.RetryBackoff;
import com.lunchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CouponConsistencyJobTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);

    private final CouponConsistencyService consistency = mock(CouponConsistencyService.class);
    private final BatchExecutionService executions = mock(BatchExecutionService.class);
    private final BatchExecutionQueryService query = mock(BatchExecutionQueryService.class);
    private final RetryBackoff backoff = mock(RetryBackoff.class);
    private final CouponConsistencyJob job = new CouponConsistencyJob(consistency, executions,
            new CouponBatchExecutionService(CouponTestProperties.defaults(), executions, query, backoff));

    @BeforeEach
    void setUp() {
        when(executions.businessDate()).thenReturn(DATE.plusDays(1));
        when(query.findByBusinessDate(DATE)).thenReturn(List.of(new BatchExecutionRecord(1L, "COUPON_VERIFY",
                DATE, "RUNNING", DATE.atStartOfDay(), null, null, "batch-1", 0, null, 1)));
    }

    @Test
    void 전날의_불일치_건수를_실행_결과에_기록한다() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        LocalDate date = today.minusDays(1);
        when(executions.businessDate()).thenReturn(today);
        when(executions.claim("COUPON_VERIFY", date)).thenReturn(true);
        when(consistency.verify(date, 1)).thenReturn(2);

        job.verifyCoupons();

        verify(executions).succeedAttempt("COUPON_VERIFY", date, 1, 2);
    }

    @Test
    void 이미_실행했으면_다시_검증하지_않는다() {
        when(executions.businessDate()).thenReturn(LocalDate.of(2026, 10, 21));
        job.verifyCoupons();
        verifyNoInteractions(consistency);
    }

    @Test
    void 일반_실행_오류도_두번_재시도한_뒤_성공할_수_있다() {
        when(executions.claim("COUPON_VERIFY", DATE)).thenReturn(true);
        when(executions.recordRetryAttempt(eq("COUPON_VERIFY"), eq(DATE), eq(1), anyString()))
                .thenReturn(true, true, false);
        when(executions.renewAttempt("COUPON_VERIFY", DATE, 1)).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        when(backoff.pause(2)).thenReturn(true);
        when(consistency.verify(DATE, 1)).thenThrow(new IllegalStateException("first"))
                .thenThrow(new IllegalStateException("second")).thenReturn(3);

        job.verifyCoupons();

        verify(consistency, times(3)).verify(DATE, 1);
        verify(executions).succeedAttempt("COUPON_VERIFY", DATE, 1, 3);
        verify(executions, never()).failAttempt(anyString(), any(), eq(1), anyString());
    }

    @Test
    void 세번_모두_실패하면_더_실행하지_않고_실패로_확정한다() {
        when(executions.claim("COUPON_VERIFY", DATE)).thenReturn(true);
        when(executions.recordRetryAttempt(eq("COUPON_VERIFY"), eq(DATE), eq(1), anyString()))
                .thenReturn(true, true, false);
        when(executions.renewAttempt("COUPON_VERIFY", DATE, 1)).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        when(backoff.pause(2)).thenReturn(true);
        when(consistency.verify(DATE, 1)).thenThrow(new IllegalStateException("still failed"));

        job.verifyCoupons();

        verify(consistency, times(3)).verify(DATE, 1);
        verify(executions).failAttempt("COUPON_VERIFY", DATE, 1, "IllegalStateException: still failed");
        verify(backoff, never()).pause(3);
    }

    @Test
    void 재시도_대기_중_실행권을_잃으면_업무를_더_실행하지_않는다() {
        when(executions.claim("COUPON_VERIFY", DATE)).thenReturn(true);
        when(executions.recordRetryAttempt(eq("COUPON_VERIFY"), eq(DATE), eq(1), anyString())).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        when(consistency.verify(DATE, 1)).thenThrow(new IllegalStateException("failed"));

        job.verifyCoupons();

        verify(consistency).verify(DATE, 1);
        verify(executions, never()).succeedAttempt(anyString(), any(), eq(1), any());
        verify(executions).failAttempt("COUPON_VERIFY", DATE, 1, "IllegalStateException: failed");
    }
}
