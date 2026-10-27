package com.lunchcatch.member.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.member.service.KakaoUnlinkRetryService;
import com.lunchcatch.ops.service.BatchExecutionService;
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
        // 이어받기 경로는 영업일을 넘겨받아 묻지 않는다
        lenient().when(executions.businessDate()).thenReturn(BUSINESS_DATE);
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
        IllegalStateException failure = new IllegalStateException("boom");
        doThrow(failure).when(kakaoUnlinkRetryService).retryPending();

        assertThatThrownBy(() -> scheduler.retryPendingUnlinks()).isSameAs(failure);

        verify(executions).fail(eq(KakaoUnlinkRetryScheduler.JOB_NAME), eq(BUSINESS_DATE), anyString());
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE));
    }

    @Test
    void 실패_기록까지_실패해도_원래_예외를_유지한다() {
        when(executions.claim(KakaoUnlinkRetryScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("원래 실패");
        IllegalStateException recordFailure = new IllegalStateException("실패 기록 오류");
        doThrow(failure).when(kakaoUnlinkRetryService).retryPending();
        doThrow(recordFailure).when(executions).fail(eq(KakaoUnlinkRetryScheduler.JOB_NAME), eq(BUSINESS_DATE), anyString());

        assertThatThrownBy(() -> scheduler.retryPendingUnlinks()).isSameAs(failure).hasSuppressedException(recordFailure);

        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE));
    }

    /*
     * 멈춘 실행을 이어받은 서버와 관리자 수동 재실행이 이 길로 다시 돌린다.
     * 행을 닫는 일은 BatchJobRunner 가 한다.
     */
    @Test
    @DisplayName("이어받은 서버가 부르면 재시도를 돌리고 행은 직접 닫지 않는다")
    void 이어받은_서버가_부르면_재시도를_돌리고_행은_닫지_않는다() {
        scheduler.run(BUSINESS_DATE, 2);

        verify(kakaoUnlinkRetryService).retryPending();
        verifyNoInteractions(executions);
        assertThat(scheduler.jobName()).isEqualTo("MEMBER_KAKAO_UNLINK_RETRY");
    }
}
