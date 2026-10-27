package com.lunchcatch.billing.service;

import com.lunchcatch.billing.dto.DateRange;
import com.lunchcatch.billing.dto.SpendGroupBy;
import com.lunchcatch.billing.dto.SpendSummaryItem;
import com.lunchcatch.billing.dto.SpendSummaryResponse;
import com.lunchcatch.billing.repository.DeductSumRow;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.billing.repository.ReserveSumRow;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주의 캠페인별 일별 소진 집계다. 집계 테이블이 없어서 원장에서 직접 계산한다.
 *
 * 그래서 기간을 31일로 막는다. DEDUCT 는 노출당 한 줄이고 날짜를 DB 함수로 자르지 않으므로,
 * 하루씩 기록 시각 범위로 묻고 캠페인별로 묶는다. 질의는 최대 31번이고 모두 인덱스 범위 조회다.
 * RESERVE 는 영업일 컬럼이 있어 기간 전체를 한 번에 묻는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerSpendSummaryService {

    private static final int MAX_DAYS = 31;

    private final PointLedgerRepository pointLedgerRepository;

    public SpendSummaryResponse summarize(Long ownerId, SpendGroupBy groupBy,
                                          LocalDate from, LocalDate to, Long campaignId) {
        DateRange range = DateRange.of(from, to, MAX_DAYS);
        Map<Key, Totals> totals = new HashMap<>();

        for (LocalDate day : range.dates()) {
            List<DeductSumRow> deducted = pointLedgerRepository.sumDeductedByCampaign(
                    ownerId, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), campaignId);
            for (DeductSumRow row : deducted) {
                totals.computeIfAbsent(key(groupBy, day, row.campaignId()), k -> new Totals())
                        .addSpent(-row.amount(), row.count());
            }
        }
        for (ReserveSumRow row : pointLedgerRepository.sumReservedByBusinessDate(
                ownerId, range.from(), range.to(), campaignId)) {
            totals.computeIfAbsent(key(groupBy, row.businessDate(), row.campaignId()), k -> new Totals())
                    .addReserved(-row.amount());
        }

        List<SpendSummaryItem> items = totals.entrySet().stream()
                .map(entry -> new SpendSummaryItem(entry.getKey().date(), entry.getKey().campaignId(),
                        entry.getValue().spent, entry.getValue().reserved, entry.getValue().impressions))
                .sorted(Comparator.comparing(SpendSummaryItem::date, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(SpendSummaryItem::campaignId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        return new SpendSummaryResponse(items);
    }

    // 묶는 기준에 들지 않는 축은 null 로 비워 같은 칸으로 합쳐지게 한다
    private Key key(SpendGroupBy groupBy, LocalDate date, Long campaignId) {
        return switch (groupBy) {
            case DAY -> new Key(date, null);
            case CAMPAIGN -> new Key(null, campaignId);
            case DAY_CAMPAIGN -> new Key(date, campaignId);
        };
    }

    private record Key(LocalDate date, Long campaignId) {
    }

    private static final class Totals {
        private long spent;
        private long reserved;
        private long impressions;

        void addSpent(long amount, long count) {
            this.spent += amount;
            this.impressions += count;
        }

        void addReserved(long amount) {
            this.reserved += amount;
        }
    }
}
