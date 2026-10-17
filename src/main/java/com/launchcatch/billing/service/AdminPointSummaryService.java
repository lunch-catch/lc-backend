package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.AdminPointSummaryItem;
import com.launchcatch.billing.dto.AdminPointSummaryResponse;
import com.launchcatch.billing.dto.DateRange;
import com.launchcatch.billing.dto.PeriodBucket;
import com.launchcatch.billing.dto.SummaryPeriod;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.BusinessDateSumRow;
import com.launchcatch.billing.repository.FlowRow;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.global.exception.CommonErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자의 점주별 일, 주, 월 합계다.
 *
 * 날짜는 DB 함수로 자르지 않고 서버가 정한다. RESERVE 와 RELEASE 는 영업일 컬럼으로 묶고,
 * CHARGE, REFUND, ADJUST 는 몇십 건뿐이라 읽어서 서버가 구간에 넣는다. CHARGE 와 REFUND 는 기록 시각의 KST 날짜,
 * ADJUST 는 정정 대상 영업일(없으면 기록 시각의 KST 날짜)로 넣는다. 분석의 소진액과 같은 귀속이다.
 *
 * DEDUCT 는 노출당 한 줄이라 기간 합계를 위해 훑지 않는다. 영업일마다 RELEASE = RESERVE - DEDUCT 이므로
 * 지난 영업일의 소진은 -(RESERVE 합 + RELEASE 합) 으로 구한다. 오늘 영업일만 13:30 해제 전일 수 있어서
 * 그날 RELEASE 가 아직 없으면 하루치 DEDUCT 를 직접 읽는다.
 * 13:30 해제가 실패한 영업일은 00:00 보정 해제가 들어오기 전까지 소진이 실제보다 크게 보인다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPointSummaryService {

    private final PointLedgerRepository pointLedgerRepository;
    private final Clock clock;

    public AdminPointSummaryResponse summarize(Long ownerId, SummaryPeriod period,
                                               LocalDate from, LocalDate to, String sortDir) {
        if (ownerId == null || period == null) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        boolean descending = isDescending(sortDir);
        DateRange range = new DateRange(from, to);
        List<PeriodBucket> buckets = period.buckets(range);

        List<Totals> totals = new ArrayList<>();
        Map<LocalDate, Totals> byDate = new HashMap<>();
        for (PeriodBucket bucket : buckets) {
            Totals bucketTotals = new Totals();
            totals.add(bucketTotals);
            for (LocalDate date : new DateRange(bucket.start(), bucket.end()).dates()) {
                byDate.put(date, bucketTotals);
            }
        }

        addFlows(ownerId, range, byDate);
        addReservations(ownerId, range, byDate);

        List<AdminPointSummaryItem> items = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            items.add(totals.get(i).toItem(buckets.get(i).label()));
        }
        if (descending) {
            items = items.reversed();
        }
        return new AdminPointSummaryResponse(ownerId, period, from, to, items);
    }

    // 정렬 기준은 시간 하나뿐이라 방향만 받는다. 모르는 값은 거절한다
    private boolean isDescending(String sortDir) {
        if (sortDir == null || "asc".equalsIgnoreCase(sortDir)) {
            return false;
        }
        if ("desc".equalsIgnoreCase(sortDir)) {
            return true;
        }
        throw new BillingException(CommonErrorCode.INVALID_INPUT);
    }

    private void addFlows(Long ownerId, DateRange range, Map<LocalDate, Totals> byDate) {
        for (FlowRow row : pointLedgerRepository.findFlowRows(
                ownerId, range.startInclusive(), range.endExclusive())) {
            byDate.get(row.attributedDate()).addFlow(row.entryType(), row.amount());
        }
    }

    private void addReservations(Long ownerId, DateRange range, Map<LocalDate, Totals> byDate) {
        Map<LocalDate, Long> reserve = new HashMap<>();
        Map<LocalDate, Long> release = new HashMap<>();
        for (BusinessDateSumRow row : pointLedgerRepository.sumReserveAndReleaseByBusinessDate(
                ownerId, range.from(), range.to())) {
            (row.entryType() == EntryType.RESERVE ? reserve : release).put(row.businessDate(), row.amount());
        }

        LocalDate today = LocalDate.now(clock);
        Set<LocalDate> dates = new HashSet<>(reserve.keySet());
        dates.addAll(release.keySet());
        for (LocalDate date : dates) {
            long reserved = -reserve.getOrDefault(date, 0L);
            long released = release.getOrDefault(date, 0L);
            // 오늘은 해제 전일 수 있어 DEDUCT 를 직접 읽고, 지난 날은 예약에서 해제를 뺀 값이 곧 소진이다
            long spent = date.equals(today) && released == 0
                    ? -pointLedgerRepository.sumByCreatedAt(ownerId, EntryType.DEDUCT,
                            date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                    : reserved - released;
            byDate.get(date).addReservation(reserved, released, spent);
        }
    }

    private static final class Totals {
        private long charge;
        private long spent;
        private long reserved;
        private long released;
        private long refund;
        private long adjust;

        void addFlow(EntryType type, long amount) {
            switch (type) {
                case CHARGE -> charge += amount;
                case REFUND -> refund += -amount;
                case ADJUST -> {
                    adjust += amount;
                    // 양수 조정은 과오 차감의 정정이라 소진에서 뺀다
                    if (amount > 0) {
                        spent -= amount;
                    }
                }
                default -> throw new IllegalArgumentException("구간 합계에 쓰지 않는 종류입니다: " + type);
            }
        }

        void addReservation(long reservedAmount, long releasedAmount, long spentAmount) {
            reserved += reservedAmount;
            released += releasedAmount;
            spent += spentAmount;
        }

        AdminPointSummaryItem toItem(String label) {
            return new AdminPointSummaryItem(label, charge, spent, reserved, released, refund, adjust);
        }
    }
}
