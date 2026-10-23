package com.launchcatch.campaign.template.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.template.service.TemplateVersionPurgeService;
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
class TemplateVersionPurgeSchedulerTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 7);

    @Mock TemplateVersionPurgeService templateVersionPurgeService;
    @Mock BatchExecutionService executions;

    private TemplateVersionPurgeScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TemplateVersionPurgeScheduler(templateVersionPurgeService, executions);
        // 이어받기 경로는 영업일을 넘겨받아 묻지 않는다
        lenient().when(executions.businessDate()).thenReturn(BUSINESS_DATE);
    }

    @Test
    @DisplayName("점유에 성공하면 삭제를 돌리고 처리 건수와 함께 성공으로 닫는다")
    void 점유에_성공하면_삭제를_돌리고_성공으로_닫는다() {
        when(executions.claim(TemplateVersionPurgeScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        when(templateVersionPurgeService.purgeSoftDeleted()).thenReturn(5);

        scheduler.purgeSoftDeletedVersions();

        verify(templateVersionPurgeService).purgeSoftDeleted();
        verify(executions).succeed(TemplateVersionPurgeScheduler.JOB_NAME, BUSINESS_DATE, 5);
    }

    @Test
    @DisplayName("다른 배치 서버가 점유했으면 돌리지 않는다")
    void 다른_배치_서버가_점유했으면_돌리지_않는다() {
        when(executions.claim(TemplateVersionPurgeScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(false);

        scheduler.purgeSoftDeletedVersions();

        verify(templateVersionPurgeService, never()).purgeSoftDeleted();
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE), anyInt());
    }

    /*
     * 점유한 행을 RUNNING 으로 남기면 2분 뒤 다른 서버가 이어받아 "다시 돌릴 경로가 없다" 로
     * 닫고 관리자에게 알린다. 실패도 이 자리에서 닫는다.
     */
    @Test
    @DisplayName("삭제가 터지면 실패로 닫는다")
    void 삭제가_터지면_실패로_닫는다() {
        when(executions.claim(TemplateVersionPurgeScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("boom");
        doThrow(failure).when(templateVersionPurgeService).purgeSoftDeleted();

        assertThatThrownBy(() -> scheduler.purgeSoftDeletedVersions()).isSameAs(failure);

        verify(executions).fail(eq(TemplateVersionPurgeScheduler.JOB_NAME), eq(BUSINESS_DATE), anyString());
        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE), anyInt());
    }

    @Test
    void 실패_기록까지_실패해도_원래_예외를_유지한다() {
        when(executions.claim(TemplateVersionPurgeScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("원래 실패");
        IllegalStateException recordFailure = new IllegalStateException("실패 기록 오류");
        doThrow(failure).when(templateVersionPurgeService).purgeSoftDeleted();
        doThrow(recordFailure).when(executions)
                .fail(eq(TemplateVersionPurgeScheduler.JOB_NAME), eq(BUSINESS_DATE), anyString());

        assertThatThrownBy(() -> scheduler.purgeSoftDeletedVersions())
                .isSameAs(failure).hasSuppressedException(recordFailure);

        verify(executions, never()).succeed(anyString(), eq(BUSINESS_DATE), anyInt());
    }

    /*
     * 멈춘 실행을 이어받은 서버와 관리자 수동 재실행이 이 길로 다시 돌린다.
     * 행을 닫는 일은 BatchJobRunner 가 한다. 이 길은 지운 건수를 돌려주기만 한다.
     */
    @Test
    @DisplayName("이어받은 서버가 부르면 지우고 삭제 건수를 돌려주며 행은 직접 닫지 않는다")
    void 이어받은_서버가_부르면_삭제_건수를_돌려주고_행은_닫지_않는다() {
        when(templateVersionPurgeService.purgeSoftDeleted()).thenReturn(7);

        Integer deleted = scheduler.runCounting(BUSINESS_DATE, 2);

        assertThat(deleted).isEqualTo(7);
        verifyNoInteractions(executions);
        assertThat(scheduler.jobName()).isEqualTo("CAMPAIGN_TEMPLATE_VERSION_PURGE");
    }

    @Test
    @DisplayName("run 은 runCounting 과 같이 지우고 행은 직접 닫지 않는다")
    void run은_지우기만_하고_행은_닫지_않는다() {
        when(templateVersionPurgeService.purgeSoftDeleted()).thenReturn(7);

        scheduler.run(BUSINESS_DATE, 2);

        verify(templateVersionPurgeService).purgeSoftDeleted();
        verifyNoInteractions(executions);
    }
}
