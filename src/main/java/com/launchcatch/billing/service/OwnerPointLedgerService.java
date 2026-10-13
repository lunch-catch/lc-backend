package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.OwnerLedgerItem;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.LedgerRow;
import com.launchcatch.billing.repository.OwnerLedgerCondition;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주의 포인트 변동 내역이다. 항상 로그인한 점주 자신의 행만 읽는다.
 *
 * DEDUCT 는 노출당 한 줄이라 목록에 내리지 않는다. 종류를 지정하지 않으면 DEDUCT 를 뺀 다섯 종류 전부를 뜻하고,
 * DEDUCT 를 직접 지정하면 거절한다. 종류를 항상 명시해서 질의하는 이유는 소진 행이 섞인 채 기록 시각
 * 순서로 훑으면 그 행을 전부 건너뛰어야 하기 때문이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerPointLedgerService {

    private static final Set<EntryType> VISIBLE_TYPES = EnumSet.complementOf(EnumSet.of(EntryType.DEDUCT));
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PointLedgerRepository pointLedgerRepository;

    public CursorPageResponse<OwnerLedgerItem> list(Long ownerId, Collection<EntryType> types,
                                                    LocalDate from, LocalDate to, Long campaignId,
                                                    String pageToken, int pageSize) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        int size = pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LocalDateTime cursorCreatedAt = null;
        Long cursorId = null;
        PageCursor cursor = PageTokens.decode(pageToken);
        if (cursor != null) {
            cursorCreatedAt = parseOrNull(cursor.sortValue());
            cursorId = cursorCreatedAt == null ? null : cursor.id();
        }

        List<LedgerRow> rows = pointLedgerRepository.findOwnerLedger(new OwnerLedgerCondition(
                ownerId,
                visibleTypes(types),
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                campaignId,
                cursorCreatedAt,
                cursorId,
                size + 1));

        boolean hasNext = rows.size() > size;
        List<LedgerRow> page = hasNext ? rows.subList(0, size) : rows;
        String nextPageToken = hasNext ? nextToken(page.get(page.size() - 1)) : null;
        return CursorPageResponse.of(page.stream().map(this::toItem).toList(), nextPageToken);
    }

    private Collection<EntryType> visibleTypes(Collection<EntryType> requested) {
        if (requested == null || requested.isEmpty()) {
            return VISIBLE_TYPES;
        }
        if (requested.contains(EntryType.DEDUCT)) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        return EnumSet.copyOf(requested);
    }

    // 토큰에 든 시각을 읽지 못하면 위조나 훼손으로 보고 첫 페이지로 시작한다
    private LocalDateTime parseOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String nextToken(LedgerRow last) {
        return PageTokens.encode(new PageCursor(last.id(), last.createdAt().toString()));
    }

    private OwnerLedgerItem toItem(LedgerRow row) {
        return new OwnerLedgerItem(row.id(), row.entryType(), row.amount(), row.campaignId(),
                row.businessDate(), row.paymentId(), row.createdAt().atZone(ClockConfig.ZONE).toOffsetDateTime());
    }
}
