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
import com.launchcatch.global.response.PageResponse;
import java.time.LocalDate;
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
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final PointLedgerRepository pointLedgerRepository;

    public PageResponse<AdminLedgerItem> search(Long ownerId, EntryType type, LocalDate from, LocalDate to,
                                                Long campaignId, int page, int size, String sortDir) {
        DateRange range = DateRange.of(from, to, MAX_DAYS);
        if (page < 0) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        int pageSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        AdminLedgerCondition condition = new AdminLedgerCondition(ownerId, type, range.startInclusive(),
                range.endExclusive(), campaignId, isAscending(sortDir), (long) page * pageSize, pageSize);

        long total = pointLedgerRepository.countAdminLedger(condition);
        if (total == 0) {
            return PageResponse.of(List.of(), page, pageSize, 0);
        }
        BalanceTimeline timeline = new BalanceTimeline(
                pointLedgerRepository.sumBalanceBefore(ownerId, range.startInclusive()),
                pointLedgerRepository.findBalanceSteps(ownerId, range.startInclusive(), range.endExclusive()));
        List<AdminLedgerItem> items = pointLedgerRepository.findAdminLedger(condition).stream()
                .map(row -> toItem(row, timeline))
                .toList();
        return PageResponse.of(items, page, pageSize, total);
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
