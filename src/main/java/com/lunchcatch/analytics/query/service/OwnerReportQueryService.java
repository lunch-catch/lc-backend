package com.lunchcatch.analytics.query.service;

import com.lunchcatch.analytics.exception.AnalyticsErrorCode;
import com.lunchcatch.analytics.exception.AnalyticsException;
import com.lunchcatch.analytics.query.AnalyticsDateRange;
import com.lunchcatch.analytics.query.dto.OwnerReportResponse;
import com.lunchcatch.analytics.query.repository.OwnerReportQueryRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OwnerReportQueryService {

    private static final int MAX_DAYS = 30;

    private final OwnerReportQueryRepository repository;

    public OwnerReportQueryService(OwnerReportQueryRepository repository) {
        this.repository = repository;
    }

    /*
     * 조회 기간이 양끝을 포함해 30일 이내인지 검증하고 점주의 일별 집계값을 읽는다.
     * 정정으로 생긴 음수 소진액도 그대로 합산하며 집계가 없으면 빈 목록과 합계 0을 반환한다.
     */
    public OwnerReportResponse query(long ownerId, LocalDate from, LocalDate to) {
        AnalyticsDateRange range = new AnalyticsDateRange(from, to);
        if (ChronoUnit.DAYS.between(range.from(), range.to()) >= MAX_DAYS) {
            throw new AnalyticsException(AnalyticsErrorCode.INVALID_PERIOD);
        }

        List<OwnerReportResponse.Item> items = repository.findBetween(ownerId, from, to).stream()
                .map(row -> new OwnerReportResponse.Item(
                        row.businessDate(), row.campaignId(), row.dailyBudget(), row.impressionUnitCost(),
                        row.validImpressionCount(), row.invalidImpressionCount(), row.wishCount(),
                        row.issuedCount(), row.redeemCount(), row.chargedAmount(), row.refundedAmount(),
                        row.spentAmount()))
                .toList();
        long totalSpentAmount = items.stream().mapToLong(OwnerReportResponse.Item::spentAmount).sum();
        return new OwnerReportResponse(from, to, totalSpentAmount, items);
    }
}
