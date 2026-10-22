package com.launchcatch.analytics.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.batch.service.EventLogRetentionService;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 배치 서버가 두 대라 같은 시각에 둘 다 시도한다. 겹치지 않는 것은 batch_execution_log 의 작업 점유가
 * 보장한다(배치 운영 문서 2장).
 */
@ExtendWith(MockitoExtension.class)
class EventLogRetentionJobTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 19);

    @Mock EventLogRetentionService retention;
    @Mock BatchExecutionService executions;

    private EventLogRetentionJob job;

    @BeforeEach
    void setUp() {
        job = new EventLogRetentionJob(retention, executions);
    }

    @Test
    @DisplayName("점유에 성공하면 지우고 삭제 건수와 함께 성공으로 닫는다")
    void 점유에_성공하면_지우고_성공으로_닫는다() {
        when(executions.businessDate()).thenReturn(BUSINESS_DATE);
        when(executions.claim(EventLogRetentionJob.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        when(retention.purge()).thenReturn(42);

        job.purgeExpiredEvents();

        verify(executions).succeed(EventLogRetentionJob.JOB_NAME, BUSINESS_DATE, 42);
    }

    @Test
    @DisplayName("다른 배치 서버가 점유했으면 지우지 않는다")
    void 다른_서버가_점유했으면_지우지_않는다() {
        when(executions.businessDate()).thenReturn(BUSINESS_DATE);
        when(executions.claim(EventLogRetentionJob.JOB_NAME, BUSINESS_DATE)).thenReturn(false);

        job.purgeExpiredEvents();

        verify(retention, never()).purge();
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE), anyInt());
    }

    /*
     * 점유한 행을 RUNNING 으로 남기면 2분 뒤 다른 서버가 이어받는다. 실패도 이 자리에서 닫는다.
     */
    @Test
    @DisplayName("삭제가 터지면 실패로 닫고 예외를 그대로 던진다")
    void 삭제가_터지면_실패로_닫는다() {
        when(executions.businessDate()).thenReturn(BUSINESS_DATE);
        when(executions.claim(EventLogRetentionJob.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("boom");
        doThrow(failure).when(retention).purge();

        assertThatThrownBy(() -> job.purgeExpiredEvents()).isSameAs(failure);

        verify(executions).fail(eq(EventLogRetentionJob.JOB_NAME), eq(BUSINESS_DATE), anyString());
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE), anyInt());
    }

    @Test
    @DisplayName("실패 기록까지 실패해도 원래 예외를 유지한다")
    void 실패_기록까지_실패해도_원래_예외를_유지한다() {
        when(executions.businessDate()).thenReturn(BUSINESS_DATE);
        when(executions.claim(EventLogRetentionJob.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("원래 실패");
        IllegalStateException recordFailure = new IllegalStateException("실패 기록 오류");
        doThrow(failure).when(retention).purge();
        doThrow(recordFailure).when(executions)
                .fail(eq(EventLogRetentionJob.JOB_NAME), eq(BUSINESS_DATE), anyString());

        assertThatThrownBy(() -> job.purgeExpiredEvents())
                .isSameAs(failure).hasSuppressedException(recordFailure);
    }

    /*
     * 행을 닫는 일은 BatchJobRunner 가 한다. 이 길은 지운 건수를 돌려주기만 한다.
     */
    @Test
    @DisplayName("이어받은 서버가 부르면 지우고 삭제 건수를 돌려주며 행은 직접 닫지 않는다")
    void 이어받은_서버가_부르면_삭제_건수를_돌려주고_행은_닫지_않는다() {
        when(retention.purge()).thenReturn(7);

        Integer deleted = job.runCounting(BUSINESS_DATE, 2);

        assertThat(deleted).isEqualTo(7);
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE), anyInt());
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE));
        assertThat(job.jobName()).isEqualTo("EVENT_LOG_RETENTION");
    }

    @Test
    @DisplayName("run 은 runCounting 과 같이 지우고 행은 직접 닫지 않는다")
    void run은_지우기만_하고_행은_닫지_않는다() {
        when(retention.purge()).thenReturn(7);

        job.run(BUSINESS_DATE, 2);

        verify(retention).purge();
        verifyNoInteractions(executions);
    }
}
