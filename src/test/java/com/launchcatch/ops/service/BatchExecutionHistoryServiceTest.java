package com.launchcatch.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import com.launchcatch.ops.dto.BatchExecutionHistoryItem;
import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.entity.BatchStatus;
import com.launchcatch.ops.exception.OpsErrorCode;
import com.launchcatch.ops.repository.BatchExecutionLogRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 관리자 배치 실행 이력 조회(#575)의 조건 검사, 커서, retryable 판정을 본다.
 *
 * 오류 코드는 명세(admin-api-spec.md 의 배치 실행 이력 조회)가 정한다. 기간 위반은 OPS-004,
 * 형식 위반은 COMMON-002 다. 저장소 공통 PageTokens 는 잘못된 토큰을 첫 페이지로 보지만 이 API 는
 * 명세대로 400 으로 답한다.
 */
@ExtendWith(MockitoExtension.class)
class BatchExecutionHistoryServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 24);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Mock
    private BatchExecutionLogRepository logs;

    @Mock
    private DailyJobRunner runner;

    @InjectMocks
    private BatchExecutionHistoryService service;

    private static BatchExecutionLog row(long id, String jobName, LocalDate date, BatchStatus status) {
        BatchExecutionLog row = BatchExecutionLog.running(jobName, date, "batch-02");
        ReflectionTestUtils.setField(row, "id", id);
        ReflectionTestUtils.setField(row, "status", status);
        ReflectionTestUtils.setField(row, "createdAt", date.atTime(0, 0));
        if (status != BatchStatus.RUNNING) {
            ReflectionTestUtils.setField(row, "finishedAt", date.atTime(0, 3, 12));
        }
        if (status == BatchStatus.FAILED) {
            ReflectionTestUtils.setField(row, "failureReason", "원본을 읽지 못했다");
        }
        return row;
    }

    private void stubRows(List<BatchExecutionLog> rows) {
        when(logs.searchHistory(any(), any(), any(), any(), any(), any(), any())).thenReturn(rows);
    }

    private static void assertError(Runnable call, Object expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("from이나 to가 없으면 OPS-004다")
    void 기간이_없으면_OPS_004() {
        assertError(() -> service.search(null, "2026-09-30", null, null, null, null),
                OpsErrorCode.INVALID_PERIOD);
        assertError(() -> service.search("2026-09-24", " ", null, null, null, null),
                OpsErrorCode.INVALID_PERIOD);
        verifyNoInteractions(logs);
    }

    @Test
    @DisplayName("to가 from보다 앞이면 OPS-004다")
    void 기간이_거꾸로면_OPS_004() {
        assertError(() -> service.search("2026-09-30", "2026-09-24", null, null, null, null),
                OpsErrorCode.INVALID_PERIOD);
    }

    /*
     * from 부터 세어 31일까지 받는다. 9월 1일부터면 10월 1일이 31일째이고 10월 2일이 32일째다.
     */
    @Test
    @DisplayName("기간은 from부터 세어 31일까지 받고 32일째부터 OPS-004다")
    void 기간은_31일까지() {
        stubRows(List.of());

        service.search("2026-09-01", "2026-10-01", null, null, null, null);

        assertError(() -> service.search("2026-09-01", "2026-10-02", null, null, null, null),
                OpsErrorCode.INVALID_PERIOD);
    }

    @Test
    @DisplayName("날짜 형식이 아니면 COMMON-002다")
    void 날짜_형식이_아니면_COMMON_002() {
        assertError(() -> service.search("2026-9-24", "2026-09-30", null, null, null, null),
                CommonErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("status가 세 값이 아니면 COMMON-002다")
    void 상태_값이_아니면_COMMON_002() {
        assertError(() -> service.search("2026-09-24", "2026-09-30", null, "DONE", null, null),
                CommonErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("pageSize가 1~100 밖이면 COMMON-002다")
    void 페이지_크기가_범위_밖이면_COMMON_002() {
        assertError(() -> service.search("2026-09-24", "2026-09-30", null, null, null, 0),
                CommonErrorCode.INVALID_INPUT);
        assertError(() -> service.search("2026-09-24", "2026-09-30", null, null, null, 101),
                CommonErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("풀 수 없는 pageToken은 첫 페이지로 보지 않고 COMMON-002다")
    void 풀_수_없는_토큰은_COMMON_002() {
        assertError(() -> service.search("2026-09-24", "2026-09-30", null, null, "not-a-token", null),
                CommonErrorCode.INVALID_INPUT);
        String badDate = PageTokens.encode(new PageCursor(10L, "어제"));
        assertError(() -> service.search("2026-09-24", "2026-09-30", null, null, badDate, null),
                CommonErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("조건을 그대로 넘기고 pageSize가 없으면 20개에 하나를 더 읽는다")
    void 조건을_넘기고_기본_크기에_하나를_더_읽는다() {
        stubRows(List.of());

        service.search("2026-09-24", "2026-09-30", "DAILY_0000", "FAILED", null, null);

        verify(logs).searchHistory(eq(FROM), eq(TO), eq("DAILY_0000"), eq(BatchStatus.FAILED),
                isNull(), isNull(), eq(PageRequest.of(0, 21)));
    }

    @Test
    @DisplayName("빈 jobName과 status는 조건으로 넘기지 않는다")
    void 빈_조건은_넘기지_않는다() {
        stubRows(List.of());

        service.search("2026-09-24", "2026-09-30", "", "", "", 5);

        verify(logs).searchHistory(eq(FROM), eq(TO), isNull(), isNull(), isNull(), isNull(),
                eq(PageRequest.of(0, 6)));
    }

    /*
     * 한 건 더 읽혔으면 다음 페이지가 있다. 커서는 마지막으로 내준 항목의 (영업일, runId)다.
     * 그 토큰을 다시 받으면 같은 두 값을 저장소에 넘겨 그 뒤부터 읽는다.
     */
    @Test
    @DisplayName("한 건 더 읽히면 마지막 항목의 영업일과 runId로 다음 토큰을 만들고 그 토큰을 다시 받으면 그 뒤부터 읽는다")
    void 다음_페이지_토큰을_만들고_다시_받는다() {
        stubRows(List.of(
                row(30, "DAILY_0000", TO, BatchStatus.SUCCESS),
                row(29, "LOAD_DAILY_CANDIDATE", TO, BatchStatus.SUCCESS),
                row(12, "DAILY_0000", FROM, BatchStatus.SUCCESS)));

        CursorPageResponse<BatchExecutionHistoryItem> page =
                service.search("2026-09-24", "2026-09-30", null, null, null, 2);

        assertThat(page.items()).extracting(BatchExecutionHistoryItem::runId).containsExactly(30L, 29L);
        assertThat(page.nextPageToken()).isNotNull();

        service.search("2026-09-24", "2026-09-30", null, null, page.nextPageToken(), 2);

        verify(logs).searchHistory(eq(FROM), eq(TO), isNull(), isNull(), eq(TO), eq(29L),
                eq(PageRequest.of(0, 3)));
    }

    @Test
    @DisplayName("한 건 더 읽히지 않으면 마지막 페이지라 nextPageToken이 null이다")
    void 마지막_페이지는_토큰이_없다() {
        stubRows(List.of(row(30, "DAILY_0000", TO, BatchStatus.SUCCESS)));

        assertThat(service.search("2026-09-24", "2026-09-30", null, null, null, 2).nextPageToken()).isNull();
    }

    @Test
    @DisplayName("retryable은 FAILED이면서 다시 실행할 수 있는 작업일 때만 참이다")
    void 재실행_가능_여부() {
        stubRows(List.of(
                row(3, "DAILY_0000", TO, BatchStatus.FAILED),
                row(2, "RESERVE_DAILY_BUDGET", TO, BatchStatus.FAILED),
                row(1, "EVENT_LOG_RETENTION", TO, BatchStatus.SUCCESS)));
        when(runner.isRetryable("DAILY_0000")).thenReturn(true);
        when(runner.isRetryable("RESERVE_DAILY_BUDGET")).thenReturn(false);

        List<BatchExecutionHistoryItem> items =
                service.search("2026-09-30", "2026-09-30", null, null, null, null).items();

        assertThat(items).extracting(BatchExecutionHistoryItem::retryable).containsExactly(true, false, false);
    }

    @Test
    @DisplayName("행의 값을 응답 필드로 옮기고 시각에 +09:00을 붙인다")
    void 응답_필드() {
        BatchExecutionLog failed = row(3, "DAILY_0000", TO, BatchStatus.FAILED);
        ReflectionTestUtils.setField(failed, "retryCount", 2);
        ReflectionTestUtils.setField(failed, "retryReason", "교착");
        ReflectionTestUtils.setField(failed, "attemptNo", 3);
        stubRows(List.of(failed));
        when(runner.isRetryable("DAILY_0000")).thenReturn(true);

        BatchExecutionHistoryItem item =
                service.search("2026-09-30", "2026-09-30", null, null, null, null).items().get(0);

        assertThat(item.runId()).isEqualTo(3L);
        assertThat(item.jobName()).isEqualTo("DAILY_0000");
        assertThat(item.businessDate()).isEqualTo(TO);
        assertThat(item.status()).isEqualTo("FAILED");
        assertThat(item.startedAt()).isEqualTo(LocalDateTime.of(2026, 9, 30, 0, 0).atOffset(ZoneOffset.ofHours(9)));
        assertThat(item.finishedAt()).isEqualTo(LocalDateTime.of(2026, 9, 30, 0, 3, 12).atOffset(ZoneOffset.ofHours(9)));
        assertThat(item.processedCount()).isNull();
        assertThat(item.executedBy()).isEqualTo("batch-02");
        assertThat(item.retryCount()).isEqualTo(2);
        assertThat(item.failureReason()).isEqualTo("원본을 읽지 못했다");
        assertThat(item.attemptNo()).isEqualTo(3);
        assertThat(item.retryable()).isTrue();
    }

    @Test
    @DisplayName("실행 중이면 finishedAt이 null이다")
    void 실행_중이면_종료_시각이_없다() {
        stubRows(List.of(row(3, "DAILY_0000", TO, BatchStatus.RUNNING)));

        assertThat(service.search("2026-09-30", "2026-09-30", null, null, null, null).items().get(0).finishedAt())
                .isNull();
    }
}
