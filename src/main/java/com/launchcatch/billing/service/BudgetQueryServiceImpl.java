package com.launchcatch.billing.service;

import com.launchcatch.billing.BudgetPacing;
import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.cache.DailySpend;
import com.launchcatch.billing.cache.DailySpendCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 광고 서빙이 후보 정렬에 쓰는 예산 소진 진행률을 돌려준다(I-06).
 *
 * 순서는 ① 계획 스냅샷(계획 ID, 단가)을 캠페인 목록으로 한 번에 조회(없는 캠페인은 결과에서 뺀다)
 * ② 당일 소진 해시를 한 번에 조회 ③ 해시가 없는 캠페인만 DB 정본으로 재구성하고 그 캠페인만 다시 읽기
 * ④ 시간대별 목표를 한 번에 조회 ⑤ 계산이다. 원장 합계는 해시 재구성 외에는 읽지 않는다.
 *
 * ①과 ④는 하루 동안 바뀌지 않는 값이라 Caffeine 캐시에서 읽는다. 캐시가 데워진 뒤에는 호출당 DB 조회가 없다.
 * 캐시에 없는 캠페인과 계획만 한 번의 쿼리로 읽는다. 예약액과 소진은 Valkey 해시에서만 읽는다.
 *
 * 읽기만 하므로 트랜잭션을 걸지 않는다. 카운터 장애는 대체값 없이 예외로 올린다.
 * 시간대별 목표 행이 없으면 가중치로 계산하고 경고 로그와 지표를 남긴다(E-7). 00:00 예약이 행을 만들지 못한 신호다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetQueryServiceImpl implements BudgetQueryService {

    static final String FALLBACK_METRIC = "billing_hourly_target_fallback_total";

    private final DailyPlanSnapshotService snapshotService;
    private final HourlyTargetService targetService;
    private final DailySpendCounter spendCounter;
    private final SpendHashRebuildService rebuildService;
    private final BillingScheduleProperties schedule;
    private final MeterRegistry meterRegistry;

    @Override
    public List<BudgetProgress> getProgress(Collection<Long> campaignIds, LocalDate businessDate, LocalDateTime at) {
        Map<Long, PlanSnapshot> plans = snapshotService.findAll(businessDate, campaignIds);
        if (plans.isEmpty()) {
            return List.of();
        }
        Map<Long, DailySpend> spends = readSpends(plans.keySet(), businessDate);
        List<Long> planIds = new ArrayList<>();
        for (PlanSnapshot plan : plans.values()) {
            planIds.add(plan.planId());
        }
        Map<Long, Map<Integer, Long>> targets = targetService.findAll(planIds);
        LocalTime time = at.toLocalTime();

        List<BudgetProgress> result = new ArrayList<>();
        for (Map.Entry<Long, PlanSnapshot> entry : plans.entrySet()) {
            Long campaignId = entry.getKey();
            PlanSnapshot plan = entry.getValue();
            DailySpend spend = spends.get(campaignId);
            Map<Integer, Long> hourlyTargets = targets.getOrDefault(plan.planId(), Map.of());
            if (hourlyTargets.isEmpty()) {
                log.warn("시간대별 목표 행이 없어 가중치로 계산합니다. campaignId={}, businessDate={}", campaignId, businessDate);
                meterRegistry.counter(FALLBACK_METRIC).increment();
            }
            long planned = BudgetPacing.plannedAmountUntil(spend.reserved(), hourlyTargets, schedule, time);
            result.add(new BudgetProgress(campaignId, spend.reserved(), plan.unitPrice(), spend.spend(),
                    planned, BudgetPacing.progressRate(spend.spend(), planned)));
        }
        return result;
    }

    // 해시가 없는 캠페인은 재구성한 뒤 그 캠페인만 다시 읽는다. 그래도 없으면 예외다
    private Map<Long, DailySpend> readSpends(Collection<Long> campaignIds, LocalDate businessDate) {
        Map<Long, DailySpend> spends = new HashMap<>(spendCounter.readAll(businessDate, List.copyOf(campaignIds)));
        List<Long> missing = new ArrayList<>();
        for (Long campaignId : campaignIds) {
            if (!spends.containsKey(campaignId)) {
                missing.add(campaignId);
            }
        }
        if (missing.isEmpty()) {
            return spends;
        }
        for (Long campaignId : missing) {
            rebuildService.rebuildIfAbsent(businessDate, campaignId);
        }
        spends.putAll(spendCounter.readAll(businessDate, missing));
        for (Long campaignId : missing) {
            if (!spends.containsKey(campaignId)) {
                throw new IllegalStateException(
                        "재구성한 뒤에도 당일 소진 해시가 없습니다. campaignId=" + campaignId + ", businessDate=" + businessDate);
            }
        }
        return spends;
    }
}
