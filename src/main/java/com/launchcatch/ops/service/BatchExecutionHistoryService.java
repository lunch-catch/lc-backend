package com.launchcatch.ops.service;

import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import com.launchcatch.ops.dto.BatchExecutionHistoryItem;
import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.entity.BatchStatus;
import com.launchcatch.ops.exception.OpsErrorCode;
import com.launchcatch.ops.exception.OpsException;
import com.launchcatch.ops.repository.BatchExecutionLogRepository;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자 배치 실행 이력 조회다(#575, 명세는 admin-api-spec.md 의 배치 실행 이력 조회).
 *
 * 조건을 문자열로 받아 여기서 검사한다. 스프링이 날짜와 enum 을 바꾸다 실패하거나 필수 파라미터가 빠지면
 * 전역 처리기가 COMMON-003 으로 답하는데, 명세는 기간 위반을 OPS-004, 형식 위반을 COMMON-002 로 정했다.
 *
 * 풀 수 없는 pageToken 과 범위 밖 pageSize 도 400 이다. 저장소 공통 PageTokens 와 billing 목록은 잘못된
 * 토큰을 첫 페이지로 보고 pageSize 를 잘라 쓰지만, 이 API 는 병합된 명세를 따른다.
 */
@Service
@RequiredArgsConstructor
public class BatchExecutionHistoryService {

    private static final int MAX_PERIOD_DAYS = 31;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final BatchExecutionLogRepository logs;
    private final DailyJobRunner runner;

    @Transactional(readOnly = true)
    public CursorPageResponse<BatchExecutionHistoryItem> search(String from, String to, String jobName,
                                                                 String status, String pageToken,
                                                                 Integer pageSize) {
        LocalDate first = requireDate(from);
        LocalDate last = requireDate(to);
        // from 부터 세어 31일까지다. 9월 1일부터면 10월 1일까지 받는다
        if (last.isBefore(first) || last.isAfter(first.plusDays(MAX_PERIOD_DAYS - 1))) {
            throw new OpsException(OpsErrorCode.INVALID_PERIOD);
        }
        BatchStatus statusFilter = parseStatus(status);
        int size = pageSize(pageSize);
        PageCursor cursor = cursor(pageToken);
        LocalDate cursorDate = cursor == null ? null : parseDate(cursor.sortValue());

        List<BatchExecutionLog> rows = logs.searchHistory(first, last, blankToNull(jobName), statusFilter,
                cursorDate, cursor == null ? null : cursor.id(), PageRequest.of(0, size + 1));

        boolean hasNext = rows.size() > size;
        List<BatchExecutionLog> page = hasNext ? rows.subList(0, size) : rows;
        String nextPageToken = hasNext ? nextToken(page.get(page.size() - 1)) : null;
        return CursorPageResponse.of(page.stream().map(this::toItem).toList(), nextPageToken);
    }

    private BatchExecutionHistoryItem toItem(BatchExecutionLog log) {
        boolean retryable = log.getStatus() == BatchStatus.FAILED && runner.isRetryable(log.getJobName());
        return BatchExecutionHistoryItem.of(log, retryable);
    }

    // 기간이 빠진 것은 형식 오류가 아니라 기간 위반이다(OPS-004)
    private static LocalDate requireDate(String value) {
        if (value == null || value.isBlank()) {
            throw new OpsException(OpsErrorCode.INVALID_PERIOD);
        }
        return parseDate(value);
    }

    // 커서에 영업일이 없으면 이 서버가 만든 토큰이 아니라 형식 오류다
    private static LocalDate parseDate(String value) {
        if (value == null) {
            throw new OpsException(CommonErrorCode.INVALID_INPUT);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new OpsException(CommonErrorCode.INVALID_INPUT, e);
        }
    }

    private static BatchStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return BatchStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new OpsException(CommonErrorCode.INVALID_INPUT, e);
        }
    }

    private static int pageSize(Integer value) {
        if (value == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new OpsException(CommonErrorCode.INVALID_INPUT);
        }
        return value;
    }

    // PageTokens.decode 는 빈 토큰과 풀 수 없는 토큰을 모두 null 로 돌려줘서 빈 토큰만 첫 페이지로 본다
    private static PageCursor cursor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        PageCursor cursor = PageTokens.decode(token);
        if (cursor == null) {
            throw new OpsException(CommonErrorCode.INVALID_INPUT);
        }
        return cursor;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nextToken(BatchExecutionLog last) {
        return PageTokens.encode(new PageCursor(last.getId(), last.getBusinessDate().toString()));
    }
}
