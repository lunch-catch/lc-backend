package com.launchcatch.member.job;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.member.service.KakaoUnlinkRetryService;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 배치 서버가 두 대라 같은 시각에 둘 다 이 작업을 시도한다. 겹치지 않는 것은
 * batch_execution_log 의 작업 점유가 보장한다(배치 운영 문서 2장).
 */
@ExtendWith(MockitoExtension.class)
class KakaoUnlinkRetrySchedulerTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 7);

    @Mock KakaoUnlinkRetryService kakaoUnlinkRetryService;
    @Mock BatchExecutionService executions;

    private KakaoUnlinkRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new KakaoUnlinkRetryScheduler(kakaoUnlinkRetryService, executions);
        when(executions.businessDate()).thenReturn(BUSINESS_DATE);
    }

    @Test
    @DisplayName("점유에 성공하면 재시도를 돌리고 성공으로 닫는다")
    void 점유에_성공하면_재시도를_돌리고_성공으로_닫는다() {
        when(executions.claim(KakaoUnlinkRetryScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(true);

        scheduler.retryPendingUnlinks();

        verify(kakaoUnlinkRetryService).retryPending();
        verify(executions).succeed(KakaoUnlinkRetryScheduler.JOB_NAME, BUSINESS_DATE);
    }

    @Test
    @DisplayName("다른 배치 서버가 점유했으면 돌리지 않는다")
    void 다른_배치_서버가_점유했으면_돌리지_않는다() {
        when(executions.claim(KakaoUnlinkRetryScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(false);

        scheduler.retryPendingUnlinks();

        verify(kakaoUnlinkRetryService, never()).retryPending();
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE));
    }

    /*
     * 점유한 행을 RUNNING 으로 남기면 2분 뒤 다른 서버가 이어받아 "다시 돌릴 경로가 없다" 로
     * 닫고 관리자에게 알린다. 실패도 이 자리에서 닫는다.
     */
    @Test
    @DisplayName("재시도가 터지면 실패로 닫는다")
    void 재시도가_터지면_실패로_닫는다() {
        when(executions.claim(KakaoUnlinkRetryScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        doThrow(new IllegalStateException("boom")).when(kakaoUnlinkRetryService).retryPending();

        scheduler.retryPendingUnlinks();

        verify(executions).fail(eq(KakaoUnlinkRetryScheduler.JOB_NAME), eq(BUSINESS_DATE), anyString());
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE));
    }
}
