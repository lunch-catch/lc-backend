package com.launchcatch.billing.service;

import com.launchcatch.billing.BudgetPacing;
import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.BudgetHourlyTarget;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.BudgetHourlyTargetRepository;
import com.launchcatch.billing.repository.DailySpend;
import com.launchcatch.billing.repository.DailySpendHashRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 광고 서빙이 후보 정렬에 쓰는 예산 소진 진행률을 돌려준다(I-06).
 *
 * 순서는 ① 그날 계획 행을 캠페인 목록으로 한 번에 조회(없는 캠페인은 결과에서 뺀다) ② 당일 소진 해시를
 * 한 번에 조회 ③ 해시가 없는 캠페인만 DB 정본으로 재구성하고 그 캠페인만 다시 읽기 ④ 시간대별 목표를
 * 한 번에 조회 ⑤ 계산이다. 원장 합계는 해시 재구성 외에는 읽지 않는다.
 *
 * 읽기만 하므로 트랜잭션을 걸지 않는다. 카운터 장애는 대체값 없이 예외로 올린다.
 * 시간대별 목표 행이 없으면 가중치로 계산하고 경고 로그와 지표를 남긴다(E-7). 00:00 예약이 행을 만들지 못한 신호다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetQueryServiceImpl implements BudgetQueryService {

    static final String FALLBACK_METRIC = "billing_hourly_target_fallback_total";

    private final BudgetDailyPlanRepository planRepository;
    private final BudgetHourlyTargetRepository targetRepository;
    private final DailySpendHashRepository hashRepository;
    private final SpendHashRebuildService rebuildService;
    private final BillingScheduleProperties schedule;
    private final MeterRegistry meterRegistry;

    @Override
    public List<BudgetProgress> getProgress(Collection<Long> campaignIds, LocalDate businessDate, LocalDateTime at) {
        Map<Long, BudgetDailyPlan> plans = findPlans(campaignIds, businessDate);
        if (plans.isEmpty()) {
            return List.of();
        }
        Map<Long, DailySpend> spends = readSpends(plans, businessDate);
        Map<Long, Map<Integer, Long>> targets = readTargets(plans);
        LocalTime time = at.toLocalTime();

        List<BudgetProgress> result = new ArrayList<>();
        for (BudgetDailyPlan plan : plans.values()) {
            DailySpend spend = spends.get(plan.getCampaignId());
            Map<Integer, Long> hourlyTargets = targets.getOrDefault(plan.getId(), Map.of());
            if (hourlyTargets.isEmpty()) {
                log.warn("시간대별 목표 행이 없어 가중치로 계산합니다. campaignId={}, businessDate={}",
                        plan.getCampaignId(), businessDate);
                meterRegistry.counter(FALLBACK_METRIC).increment();
            }
            long planned = BudgetPacing.plannedAmountUntil(spend.reserved(), hourlyTargets, schedule, time);
            result.add(new BudgetProgress(plan.getCampaignId(), spend.reserved(), plan.getUnitPrice(), spend.spend(),
                    planned, BudgetPacing.progressRate(spend.spend(), planned)));
        }
        return result;
    }

    // 요청한 순서를 지키고 그날 계획이 없는 캠페인은 뺀다
    private Map<Long, BudgetDailyPlan> findPlans(Collection<Long> campaignIds, LocalDate businessDate) {
        Map<Long, BudgetDailyPlan> found = new HashMap<>();
        for (BudgetDailyPlan plan : planRepository.findByBusinessDateAndCampaignIdIn(businessDate, campaignIds)) {
            found.put(plan.getCampaignId(), plan);
        }
        Map<Long, BudgetDailyPlan> ordered = new LinkedHashMap<>();
        for (Long campaignId : campaignIds) {
            if (found.containsKey(campaignId)) {
                ordered.put(campaignId, found.get(campaignId));
            }
        }
        return ordered;
    }

    // 해시가 없는 캠페인은 재구성한 뒤 그 캠페인만 다시 읽는다. 그래도 없으면 예외다
    private Map<Long, DailySpend> readSpends(Map<Long, BudgetDailyPlan> plans, LocalDate businessDate) {
        Map<Long, DailySpend> spends = new HashMap<>(hashRepository.readAll(businessDate, List.copyOf(plans.keySet())));
        List<Long> missing = new ArrayList<>();
        for (Long campaignId : plans.keySet()) {
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
        spends.putAll(hashRepository.readAll(businessDate, missing));
        for (Long campaignId : missing) {
            if (!spends.containsKey(campaignId)) {
                throw new IllegalStateException(
                        "재구성한 뒤에도 당일 소진 해시가 없습니다. campaignId=" + campaignId + ", businessDate=" + businessDate);
            }
        }
        return spends;
    }

    // 계획 ID별 시(hour)에서 목표 금액으로 가는 맵이다
    private Map<Long, Map<Integer, Long>> readTargets(Map<Long, BudgetDailyPlan> plans) {
        List<Long> planIds = new ArrayList<>();
        for (BudgetDailyPlan plan : plans.values()) {
            planIds.add(plan.getId());
        }
        Map<Long, Map<Integer, Long>> targets = new HashMap<>();
        for (BudgetHourlyTarget target : targetRepository.findByBudgetDailyPlanIdIn(planIds)) {
            targets.computeIfAbsent(target.getBudgetDailyPlanId(), id -> new HashMap<>())
                    .put((int) target.getHour(), target.getTargetAmount());
        }
        return targets;
    }
}
