package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.AdminLedgerItem;
import com.launchcatch.billing.dto.BalanceTimeline;
import com.launchcatch.billing.dto.DateRange;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.AdminLedgerCondition;
import com.launchcatch.billing.repository.AdminLedgerRow;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자의 점주별 포인트 변동 조회다.
 *
 * 변동 전후 잔액은 저장값이 아니다. 기간 시작 전의 잔액에서 출발해 잔액을 바꾸는 줄을 시간순으로
 * 누적해 두고, 페이지에 담긴 줄마다 그 누적에서 자기 위치의 값을 읽는다. 종류나 캠페인으로 걸러
 * 보더라도 누적은 걸러지지 않은 전체 줄을 기준으로 하므로 잔액이 끊기지 않는다.
 * 노출마다 쌓이는 DEDUCT 는 누적에 들어가지 않는다. 그래서 기간 안의 DEDUCT 를 훑지 않고 페이지만 읽는다.
 * 한 트랜잭션에서 읽어 시작 잔액과 누적이 같은 시점의 원장을 본다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPointLedgerService {

    private static final int MAX_DAYS = 31;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PointLedgerRepository pointLedgerRepository;

    public CursorPageResponse<AdminLedgerItem> search(Long ownerId, EntryType type, LocalDate from, LocalDate to,
                                                      Long campaignId, String pageToken, int pageSize, String sortDir) {
        DateRange range = DateRange.of(from, to, MAX_DAYS);
        if (ownerId == null) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        int size = pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        PageCursor cursor = PageTokens.decode(pageToken);
        LocalDateTime cursorCreatedAt = cursorCreatedAt(cursor);
        Long cursorId = cursorCreatedAt == null ? null : cursor.id();
        AdminLedgerCondition condition = new AdminLedgerCondition(ownerId, type, range.startInclusive(),
                range.endExclusive(), campaignId, isAscending(sortDir), cursorCreatedAt, cursorId, size + 1);

        List<AdminLedgerRow> rows = pointLedgerRepository.findAdminLedger(condition);
        if (rows.isEmpty()) {
            return CursorPageResponse.of(List.of(), null);
        }
        boolean hasNext = rows.size() > size;
        List<AdminLedgerRow> page = hasNext ? rows.subList(0, size) : rows;
        String nextPageToken = hasNext ? nextToken(page.get(page.size() - 1)) : null;
        BalanceTimeline timeline = new BalanceTimeline(
                pointLedgerRepository.sumBalanceBefore(ownerId, range.startInclusive()),
                pointLedgerRepository.findBalanceSteps(ownerId, range.startInclusive(), range.endExclusive()));
        List<AdminLedgerItem> items = page.stream()
                .map(row -> toItem(row, timeline))
                .toList();
        return CursorPageResponse.of(items, nextPageToken);
    }

    // 토큰의 기록 시각을 읽지 못하면 첫 페이지로 조회한다
    private LocalDateTime cursorCreatedAt(PageCursor cursor) {
        if (cursor == null || cursor.sortValue() == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(cursor.sortValue());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String nextToken(AdminLedgerRow last) {
        return PageTokens.encode(new PageCursor(last.id(), last.createdAt().toString()));
    }

    // 정렬 기준은 기록 시각과 ID 로 고정이라 방향만 받는다. 모르는 값은 거절한다
    private boolean isAscending(String sortDir) {
        if (sortDir == null || "desc".equalsIgnoreCase(sortDir)) {
            return false;
        }
        if ("asc".equalsIgnoreCase(sortDir)) {
            return true;
        }
        throw new BillingException(CommonErrorCode.INVALID_INPUT);
    }

    private AdminLedgerItem toItem(AdminLedgerRow row, BalanceTimeline timeline) {
        long before = timeline.before(row.createdAt(), row.id());
        // DEDUCT 는 잔액을 바꾸지 않으므로 직전 값을 그대로 둔다
        long after = row.entryType() == EntryType.DEDUCT ? before : before + row.amount();
        return new AdminLedgerItem(row.id(), row.ownerId(), row.entryType(), before, row.amount(), after,
                row.campaignId(), row.serveId(), row.pgTid(), row.source(), row.reason(),
                row.createdAt().atZone(ClockConfig.ZONE).toOffsetDateTime());
    }
}
