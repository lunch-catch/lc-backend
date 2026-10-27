package com.lunchcatch.analytics.query.service;

import com.lunchcatch.analytics.batch.InvalidReasonCode;
import com.lunchcatch.analytics.query.FraudDateRange;
import com.lunchcatch.analytics.query.InvalidImpressionResult;
import com.lunchcatch.analytics.query.InvalidImpressionSort;
import com.lunchcatch.analytics.query.SortDirection;
import com.lunchcatch.analytics.query.repository.FraudFilter;
import com.lunchcatch.analytics.query.repository.FraudQueryRepository;
import com.lunchcatch.analytics.query.InvalidImpressionRow;
import com.lunchcatch.analytics.query.ReasonTotal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/*
 * A-09 무효 노출 사유별 집계다. 일별 무효 노출을 기간 안에서 (회원, 캠페인, 사유)마다 합산한다.
 *
 * 필터와 사유별 합계는 저장된 행을 조회할 때 합산한다. 사유별 합계는 조회 조건이 허용하는 사유를 건수가 0이어도
 * 모두 담아, 화면이 사유가 빠졌는지 따로 확인하지 않게 한다.
 */
@Service
public class InvalidImpressionQueryService {

    private final FraudQueryRepository repository;

    public InvalidImpressionQueryService(FraudQueryRepository repository) {
        this.repository = repository;
    }

    public InvalidImpressionResult query(LocalDate from, LocalDate to, InvalidReasonCode reasonCode, Long campaignId,
            Long memberId, int page, int size, String sortBy, String sortDir) {
        FraudDateRange range = new FraudDateRange(from, to);
        InvalidImpressionSort sort = InvalidImpressionSort.from(sortBy);
        boolean descending = SortDirection.from(sortDir) == SortDirection.DESC;
        FraudFilter filter = new FraudFilter(range, reasonCode, campaignId, memberId);

        List<ReasonTotal> summary = summarize(filter, reasonCode);
        long totalElements = repository.countInvalid(filter);
        List<InvalidImpressionRow> items = repository.findInvalid(filter, sort, descending, page * size, size);
        return new InvalidImpressionResult(range.from(), range.to(), summary, items, page, size, totalElements);
    }

    private List<ReasonTotal> summarize(FraudFilter filter, InvalidReasonCode reasonCode) {
        Map<InvalidReasonCode, Long> totals = repository.sumInvalidByReason(filter).stream()
                .collect(Collectors.toMap(ReasonTotal::reasonCode, ReasonTotal::count));
        return Arrays.stream(InvalidReasonCode.values())
                .filter(reason -> reasonCode == null || reason == reasonCode)
                .map(reason -> new ReasonTotal(reason, totals.getOrDefault(reason, 0L)))
                .toList();
    }
}
