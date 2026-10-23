package com.launchcatch.coupon.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.coupon.service.CouponExpirationService;
import com.launchcatch.coupon.service.CouponBatchExecutionService;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CouponExpirationJobTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
    private final CouponExpirationService expiration = mock(CouponExpirationService.class);
    private final BatchExecutionService executions = mock(BatchExecutionService.class);
    private final BatchExecutionQueryService query = mock(BatchExecutionQueryService.class);
    private final CouponBatchExecutionService batchExecution = new CouponBatchExecutionService(
            executions, query, mock(RetryBackoff.class));
    private final CouponExpirationJob job = new CouponExpirationJob(expiration, executions, query, batchExecution);

    @Test
    void 만료할_회원_쿠폰이_없으면_실행_이력을_만들지_않는다() {
        when(expiration.expiringDates()).thenReturn(List.of());

        job.expireCoupons();

        verifyNoInteractions(executions, query);
    }

    @Test
    void 종료_시각이_지난_회원_쿠폰의_날짜를_점유하고_처리_건수를_기록한다() {
        when(expiration.expiringDates()).thenReturn(List.of(DATE));
        when(query.findByBusinessDate(DATE)).thenReturn(List.of(), List.of(record("RUNNING", 1)));
        when(executions.claim("COUPON_EXPIRE", DATE)).thenReturn(true);
        when(expiration.expire(DATE, 1)).thenReturn(3);

        job.expireCoupons();

        verify(executions).succeedAttempt("COUPON_EXPIRE", DATE, 1, 3);
    }

    @Test
    void 이전_성공_뒤_남은_쿠폰은_다음_주기에_다시_점유해_처리한다() {
        when(expiration.expiringDates()).thenReturn(List.of(DATE));
        when(query.findByBusinessDate(DATE)).thenReturn(List.of(record("SUCCESS", 1)), List.of(record("RUNNING", 2)));
        when(executions.rerun("COUPON_EXPIRE", DATE)).thenReturn(true);
        when(expiration.expire(DATE, 2)).thenReturn(1);

        job.expireCoupons();

        verify(executions).succeedAttempt("COUPON_EXPIRE", DATE, 2, 1);
    }

    @Test
    void 실패한_작업도_다음_주기에_다시_점유해_남은_쿠폰을_처리한다() {
        when(expiration.expiringDates()).thenReturn(List.of(DATE));
        when(query.findByBusinessDate(DATE)).thenReturn(List.of(record("FAILED", 1)), List.of(record("RUNNING", 2)));
        when(executions.rerun("COUPON_EXPIRE", DATE)).thenReturn(true);
        when(expiration.expire(DATE, 2)).thenReturn(2);

        job.expireCoupons();

        verify(executions).succeedAttempt("COUPON_EXPIRE", DATE, 2, 2);
    }

    @Test
    void 실패한_작업의_재점유에_실패하면_만료_처리를_시작하지_않는다() {
        when(expiration.expiringDates()).thenReturn(List.of(DATE));
        when(query.findByBusinessDate(DATE)).thenReturn(List.of(record("FAILED", 1)));

        job.expireCoupons();

        verify(executions).rerun("COUPON_EXPIRE", DATE);
        verify(expiration, never()).expire(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 다른_실행이_진행_중이면_중복으로_시작하지_않는다() {
        when(expiration.expiringDates()).thenReturn(List.of(DATE));
        when(query.findByBusinessDate(DATE)).thenReturn(List.of(record("RUNNING", 1)));

        job.expireCoupons();

        verifyNoInteractions(executions);
        verify(expiration, never()).expire(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 재시도를_계속할_수_없으면_해당_실행_세대의_실패를_기록한다() {
        when(expiration.expiringDates()).thenReturn(List.of(DATE));
        when(query.findByBusinessDate(DATE)).thenReturn(List.of(), List.of(record("RUNNING", 1)));
        when(executions.claim("COUPON_EXPIRE", DATE)).thenReturn(true);
        when(expiration.expire(DATE, 1)).thenThrow(new IllegalStateException("expiration failed"));

        job.expireCoupons();

        verify(executions).failAttempt(eq("COUPON_EXPIRE"), eq(DATE), eq(1), anyString());
        verify(executions, never()).succeedAttempt(anyString(), any(), eq(1), any());
    }

    private BatchExecutionRecord record(String status, int attemptNo) {
        return new BatchExecutionRecord(1L, "COUPON_EXPIRE", DATE, status, DATE.atStartOfDay(),
                null, null, "batch-1", 0, null, attemptNo);
    }
}
