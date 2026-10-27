package com.lunchcatch.billing.service;

import com.lunchcatch.billing.dto.AdminPaymentItem;
import com.lunchcatch.billing.entity.PaymentStatus;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.AdminPaymentCondition;
import com.lunchcatch.billing.repository.AdminPaymentRow;
import com.lunchcatch.billing.repository.PaymentRepository;
import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.global.exception.CommonErrorCode;
import com.lunchcatch.global.response.PageResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자의 결제(충전) 내역 조회다. 필터와 검색은 모두 정확 일치이고, 허용하지 않는 값은 COMMON-002 로 거절한다.
 * 기간은 요청 시각(created_at) 기준 KST 날짜이며 양 끝을 포함한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPaymentQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private enum SearchType {
        OWNER_ID, ORDER_ID, PG_TID
    }

    private final PaymentRepository paymentRepository;

    public PageResponse<AdminPaymentItem> search(LocalDate from, LocalDate to, PaymentStatus status,
                                                 String searchType, String keyword, int page, int size,
                                                 String sortBy, String sortDir) {
        if (page < 0 || (from != null && to != null && to.isBefore(from))) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        int pageSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        AdminPaymentCondition.Sort sort = sort(sortBy);
        boolean ascending = isAscending(sortDir);
        SearchType type = searchType == null || searchType.isBlank() ? null : searchType(searchType);
        boolean searching = keyword != null && !keyword.isBlank();
        if (searching && type == null) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        AdminPaymentCondition condition = new AdminPaymentCondition(
                searching && type == SearchType.OWNER_ID ? ownerId(keyword) : null,
                searching && type == SearchType.ORDER_ID ? keyword : null,
                searching && type == SearchType.PG_TID ? keyword : null,
                status,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                sort, ascending, (long) page * pageSize, pageSize);

        long total = paymentRepository.countAdminPayments(condition);
        if (total == 0) {
            return PageResponse.of(List.of(), page, pageSize, 0);
        }
        List<AdminPaymentItem> items = paymentRepository.findAdminPayments(condition).stream()
                .map(AdminPaymentQueryService::toItem)
                .toList();
        return PageResponse.of(items, page, pageSize, total);
    }

    // 결제 한 건을 목록과 같은 모양으로 읽는다
    public AdminPaymentItem get(Long paymentId) {
        return paymentRepository.findAdminPaymentById(paymentId)
                .map(AdminPaymentQueryService::toItem)
                .orElseThrow(() -> new BillingException(BillingErrorCode.PAYMENT_NOT_FOUND));
    }

    private SearchType searchType(String value) {
        try {
            return SearchType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT, e);
        }
    }

    private Long ownerId(String keyword) {
        try {
            return Long.valueOf(keyword.trim());
        } catch (NumberFormatException e) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT, e);
        }
    }

    private AdminPaymentCondition.Sort sort(String sortBy) {
        if (sortBy == null || "requestedAt".equals(sortBy)) {
            return AdminPaymentCondition.Sort.REQUESTED_AT;
        }
        return switch (sortBy) {
            case "amount" -> AdminPaymentCondition.Sort.AMOUNT;
            case "approvedAt" -> AdminPaymentCondition.Sort.APPROVED_AT;
            default -> throw new BillingException(CommonErrorCode.INVALID_INPUT);
        };
    }

    private boolean isAscending(String sortDir) {
        if (sortDir == null || "desc".equalsIgnoreCase(sortDir)) {
            return false;
        }
        if ("asc".equalsIgnoreCase(sortDir)) {
            return true;
        }
        throw new BillingException(CommonErrorCode.INVALID_INPUT);
    }

    static AdminPaymentItem toItem(AdminPaymentRow row) {
        AdminPaymentItem.Reconciliation reconciliation = row.outboxStatus() == null ? null
                : new AdminPaymentItem.Reconciliation(row.outboxStatus(), row.attemptCount(), row.maxRetry(),
                        kst(row.nextRetryAt()), row.lastError());
        return new AdminPaymentItem(row.id(), row.orderId(), row.pgTid(), row.ownerId(), row.amount(),
                row.amount(), row.status(), row.failureReason(), kst(row.createdAt()), kst(row.approvedAt()),
                kst(row.canceledAt()), reconciliation);
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
