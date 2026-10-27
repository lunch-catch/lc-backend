package com.lunchcatch.billing.service;

import com.lunchcatch.billing.dto.AdminRefundRequestItem;
import com.lunchcatch.billing.entity.RefundRequest;
import com.lunchcatch.billing.entity.RefundStatus;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.OwnerBalanceRow;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.billing.repository.RefundRequestRepository;
import com.lunchcatch.campaign.contract.CampaignQueryService;
import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.global.exception.CommonErrorCode;
import com.lunchcatch.global.response.PageResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자의 환불 요청 목록이다.
 *
 * 관리자 표 목록이라 페이지 번호 방식이다. 기간은 요청 시각 기준 KST 날짜이고 양 끝을 포함한다.
 * 점주의 현재 잔액과 집행 중 캠페인 여부는 행마다 묻지 않고 페이지의 점주를 모아 한 번씩 묻는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminRefundService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final RefundRequestRepository refundRequestRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final CampaignQueryService campaignQueryService;

    public PageResponse<AdminRefundRequestItem> search(RefundStatus status, Long ownerId, LocalDate from,
                                                       LocalDate to, int page, int size, String sortBy,
                                                       String sortDir) {
        if (page < 0 || (from != null && to != null && to.isBefore(from))) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        int pageSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<RefundRequest> rows = refundRequestRepository.search(status, ownerId,
                from == null ? null : from.atStartOfDay(), to == null ? null : to.plusDays(1).atStartOfDay(),
                PageRequest.of(page, pageSize, sort(sortBy, direction(sortDir))));

        List<Long> ownerIds = rows.getContent().stream().map(RefundRequest::getOwnerId).distinct().toList();
        if (ownerIds.isEmpty()) {
            return PageResponse.of(List.of(), page, pageSize, rows.getTotalElements());
        }
        Map<Long, Long> balances = pointLedgerRepository.sumBalanceByOwnerIds(ownerIds).stream()
                .collect(Collectors.toMap(OwnerBalanceRow::ownerId, OwnerBalanceRow::balance));
        Set<Long> liveOwners = campaignQueryService.findOwnerIdsWithLiveCampaign(ownerIds);
        List<AdminRefundRequestItem> items = rows.getContent().stream()
                .map(row -> toItem(row, balances.getOrDefault(row.getOwnerId(), 0L),
                        liveOwners.contains(row.getOwnerId())))
                .toList();
        return PageResponse.of(items, page, pageSize, rows.getTotalElements());
    }

    /*
     * 같은 값이면 ID 로 순서를 고정한다. 처리 시각으로 정렬하면 처리 전(NULL) 요청은 방향과 관계없이 맨 뒤다.
     * requestedAt 은 요청을 만든 시각(created_at)이다.
     */
    private static Sort sort(String sortBy, Sort.Direction direction) {
        if (sortBy == null || "requestedAt".equals(sortBy)) {
            return Sort.by(direction, "createdAt", "id");
        }
        if ("amount".equals(sortBy)) {
            return Sort.by(direction, "amount", "id");
        }
        if ("processedAt".equals(sortBy)) {
            return Sort.by(new Sort.Order(direction, "processedAt").nullsLast(), new Sort.Order(direction, "id"));
        }
        throw new BillingException(CommonErrorCode.INVALID_INPUT);
    }

    private static Sort.Direction direction(String sortDir) {
        if (sortDir == null || "desc".equalsIgnoreCase(sortDir)) {
            return Sort.Direction.DESC;
        }
        if ("asc".equalsIgnoreCase(sortDir)) {
            return Sort.Direction.ASC;
        }
        throw new BillingException(CommonErrorCode.INVALID_INPUT);
    }

    private static AdminRefundRequestItem toItem(RefundRequest row, long currentBalance, boolean hasActiveCampaign) {
        return new AdminRefundRequestItem(row.getId(), row.getOwnerId(), row.getAmount(), row.getFee(),
                row.getStatus(), row.getReason(), row.getRejectReason(), toOffset(row.getCreatedAt()),
                toOffset(row.getProcessedAt()), row.getProcessedBy(), currentBalance, hasActiveCampaign);
    }

    private static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
