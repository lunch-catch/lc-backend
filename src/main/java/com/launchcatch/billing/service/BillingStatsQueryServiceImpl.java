package com.launchcatch.billing.service;

import com.launchcatch.billing.contract.BillingStatsQueryService;
import com.launchcatch.billing.contract.CampaignBillingCount;
import com.launchcatch.billing.contract.CampaignDailyBudget;
import com.launchcatch.billing.contract.DailyLedgerTotals;
import com.launchcatch.billing.contract.OwnerCampaignLedgerSum;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.DeductionCountRow;
import com.launchcatch.billing.repository.DeductionResultRepository;
import com.launchcatch.billing.repository.LedgerSumRow;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.repository.SettlementMismatchRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 분석 배치가 영업일마다 부르는 집계 조회다. 읽기만 하므로 읽기 전용 트랜잭션 하나로 묶어 한 호출 안의 쿼리들이 같은 스냅샷을 본다.
 *
 * 영업일 귀속은 유형마다 다르다. RESERVE, RELEASE 는 원장의 영업일, DEDUCT 는 차감 판정의 영업일, ADJUST 는 정정 대상 영업일,
 * CHARGE, REFUND 는 기록 시각이다. 기록 시각의 하루는 날짜를 DB 함수로 자르지 않고 [당일 00:00, 다음 날 00:00) 범위로 묻는다.
 * 저장 시각은 Asia/Seoul 기준 LocalDateTime 이라 영업일 경계와 같다.
 *
 * 정정 대상 영업일이 없는 ADJUST(캠페인 없는 조정과 adjust_target_date 를 두기 전 행)는 기록 시각의 날에 붙인다(이슈 402).
 * 미소진 잔액과 원장 대조용 합계는 현금이 움직인 시점이 기준이라 ADJUST 도 기록 시각으로 센다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BillingStatsQueryServiceImpl implements BillingStatsQueryService {

    private final PointLedgerRepository pointLedgerRepository;
    private final DeductionResultRepository deductionResultRepository;
    private final BudgetDailyPlanRepository budgetDailyPlanRepository;
    private final SettlementMismatchRepository settlementMismatchRepository;

    @Override
    public List<OwnerCampaignLedgerSum> ledgerSums(LocalDate businessDate) {
        LocalDateTime from = businessDate.atStartOfDay();
        LocalDateTime to = businessDate.plusDays(1).atStartOfDay();
        Map<Key, Sums> merged = new LinkedHashMap<>();
        merge(merged, pointLedgerRepository.sumReserveReleaseOn(businessDate));
        merge(merged, pointLedgerRepository.sumDeductOn(businessDate));
        merge(merged, pointLedgerRepository.sumChargeRefundBetween(from, to));
        merge(merged, pointLedgerRepository.sumAdjustOn(businessDate, from, to));

        List<OwnerCampaignLedgerSum> result = new ArrayList<>(merged.size());
        merged.forEach((key, sums) -> result.add(new OwnerCampaignLedgerSum(key.ownerId(), key.campaignId(),
                sums.charge, sums.reserve, sums.deduct, sums.release, sums.refund,
                sums.positiveAdjust, sums.negativeAdjust)));
        return result;
    }

    @Override
    public List<CampaignBillingCount> billingCounts(LocalDate businessDate) {
        Map<Long, long[]> counts = new LinkedHashMap<>();
        for (DeductionCountRow row : deductionResultRepository.countByStatusOn(businessDate)) {
            long[] byStatus = counts.computeIfAbsent(row.campaignId(), id -> new long[3]);
            switch (row.status()) {
                case BILLED -> byStatus[0] += row.count();
                case NOT_BILLED_BUDGET -> byStatus[1] += row.count();
                case NOT_BILLED_PAUSED -> byStatus[2] += row.count();
            }
        }
        List<CampaignBillingCount> result = new ArrayList<>(counts.size());
        counts.forEach((campaignId, c) -> result.add(new CampaignBillingCount(campaignId, c[0], c[1], c[2])));
        return result;
    }

    @Override
    public List<CampaignDailyBudget> dailyBudgets(LocalDate businessDate) {
        return budgetDailyPlanRepository.findByBusinessDate(businessDate).stream()
                .map(plan -> new CampaignDailyBudget(plan.getCampaignId(), plan.getDailyBudget(), plan.getUnitPrice()))
                .toList();
    }

    @Override
    public List<LocalDate> correctionTargetDates(LocalDate recordedDate) {
        return pointLedgerRepository.findCorrectionTargetDates(
                recordedDate.atStartOfDay(), recordedDate.plusDays(1).atStartOfDay());
    }

    @Override
    public DailyLedgerTotals dailyTotals(LocalDate businessDate) {
        LocalDateTime from = businessDate.atStartOfDay();
        LocalDateTime to = businessDate.plusDays(1).atStartOfDay();
        return new DailyLedgerTotals(
                pointLedgerRepository.sumUnspentBefore(to),
                pointLedgerRepository.sumUnspentBetween(from, to),
                settlementMismatchRepository.countByBusinessDate(businessDate));
    }

    private void merge(Map<Key, Sums> merged, List<LedgerSumRow> rows) {
        for (LedgerSumRow row : rows) {
            Sums sums = merged.computeIfAbsent(new Key(row.ownerId(), row.campaignId()), k -> new Sums());
            long positive = Objects.requireNonNullElse(row.positive(), 0L);
            long negative = Objects.requireNonNullElse(row.negative(), 0L);
            switch (row.entryType()) {
                case CHARGE -> sums.charge += positive;
                case RESERVE -> sums.reserve += negative;
                case DEDUCT -> sums.deduct += negative;
                case RELEASE -> sums.release += positive;
                case REFUND -> sums.refund += negative;
                case ADJUST -> {
                    sums.positiveAdjust += positive;
                    sums.negativeAdjust += negative;
                }
            }
        }
    }

    private record Key(Long ownerId, Long campaignId) {
    }

    private static final class Sums {
        long charge;
        long reserve;
        long deduct;
        long release;
        long refund;
        long positiveAdjust;
        long negativeAdjust;
    }
}
