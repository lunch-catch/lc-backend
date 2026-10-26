package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.SettlementMismatchItem;
import com.launchcatch.billing.entity.MismatchCheckType;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PaymentRepository;
import com.launchcatch.billing.repository.SettlementMismatchRepository;
import com.launchcatch.billing.repository.SettlementMismatchRow;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.response.PageResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자의 대사 불일치 목록이다.
 *
 * 관리자 표 목록이라 페이지 번호 방식이다. 전체 건수(totalElements)를 매출 현황의 불일치 건수로 쓰므로
 * 전체 건수를 주지 않는 커서 방식으로 바꿀 수 없다(구현 계획 G-1).
 * 기간은 대사 대상 영업일(KST 날짜) 기준이고 양 끝을 포함한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementMismatchQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final SettlementMismatchRepository settlementMismatchRepository;
    private final PaymentRepository paymentRepository;
    private final CampaignQueryService campaignQueryService;

    public PageResponse<SettlementMismatchItem> search(MismatchCheckType checkType, Boolean resolved,
                                                       LocalDate from, LocalDate to, int page, int size,
                                                       String sortBy, String sortDir) {
        if (page < 0 || (from != null && to != null && to.isBefore(from))) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        int pageSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Sort.Direction direction = direction(sortDir);
        // 같은 시각이나 같은 영업일은 ID 로 순서를 고정해 페이지 사이에서 건이 겹치거나 빠지지 않게 한다
        Sort sort = Sort.by(direction, sortProperty(sortBy)).and(Sort.by(direction, "id"));

        Page<SettlementMismatchRow> rows = settlementMismatchRepository
                .search(checkType, Boolean.TRUE.equals(resolved), Boolean.FALSE.equals(resolved), from, to,
                        PageRequest.of(page, pageSize, sort));
        Map<Long, Long> paymentOwners = paymentOwners(targetIds(rows, false));
        Map<Long, Long> campaignOwners = campaignOwners(targetIds(rows, true));
        return PageResponse.from(rows.map(row -> toItem(row,
                (row.checkType().targetsCampaign() ? campaignOwners : paymentOwners).get(row.targetId()))));
    }

    private static List<Long> targetIds(Page<SettlementMismatchRow> rows, boolean campaign) {
        return rows.stream()
                .filter(row -> row.checkType().targetsCampaign() == campaign)
                .map(SettlementMismatchRow::targetId)
                .distinct()
                .toList();
    }

    // 포인트 수동 조정이 대사 건의 점주를 확인하는 출처와 같아 목록의 점주로 정정하면 BILLING-022 가 나지 않는다
    private Map<Long, Long> paymentOwners(List<Long> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        return paymentRepository.findAllById(paymentIds).stream()
                .collect(Collectors.toMap(Payment::getId, Payment::getOwnerId));
    }

    private Map<Long, Long> campaignOwners(List<Long> campaignIds) {
        if (campaignIds.isEmpty()) {
            return Map.of();
        }
        return campaignQueryService.findAllById(campaignIds).stream()
                .collect(Collectors.toMap(CampaignInfo::campaignId, CampaignInfo::ownerId));
    }

    private static String sortProperty(String sortBy) {
        if (sortBy == null || "createdAt".equals(sortBy)) {
            return "createdAt";
        }
        if ("businessDate".equals(sortBy)) {
            return "businessDate";
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

    private static SettlementMismatchItem toItem(SettlementMismatchRow row, Long ownerId) {
        return new SettlementMismatchItem(row.id(), row.checkType(), row.targetId(), ownerId, row.businessDate(),
                row.expectedValue(), row.actualValue(), row.actualValue() - row.expectedValue(),
                row.resolutionType(), toOffset(row.createdAt()), row.jobExecutionId(), toOffset(row.resolvedAt()),
                row.resolvedBy());
    }

    private static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
