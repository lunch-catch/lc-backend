package com.launchcatch.billing.service;

import com.launchcatch.billing.BudgetPacing;
import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.cache.DailySpend;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.exception.BudgetCounterUnavailableException;
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
 * 데워지기 전과 해시가 없는 구간(그날 첫 조회, Valkey 재시작 직후)은 다르다. 캐시에 없는 계획과 목표는 후보 수와 무관하게
 * 한 번의 IN 조회로 읽고, 해시가 없는 캠페인은 계획 IN 조회 1회와 원장 그룹 합 1회로 한꺼번에 재구성한다(캠페인별 쿼리 없음).
 * Valkey 쓰기만 캠페인마다 나가고, 같은 시점에 들어온 다른 호출이 같은 재구성을 반복할 수 있다. 쓰기는 "없을 때만 만드는"
 * Lua 라 정합성은 지켜지고 비용만 겹친다. 00:00 예약이 해시를 미리 만들면 이 구간은 거의 사라진다.
 * 캐시에 없는 캠페인과 계획만 한 번의 쿼리로 읽는다. 예약액과 소진은 Valkey 해시에서만 읽는다.
 *
 * 후보가 MAX_CAMPAIGN_IDS(5000)를 넘으면 아무것도 읽지 않고 IllegalArgumentException으로 거부한다.
 * 읽기만 하므로 트랜잭션을 걸지 않는다. 카운터 장애는 대체값 없이 BudgetCounterUnavailableException으로 올린다.
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
        if (campaignIds.size() > MAX_CAMPAIGN_IDS) {
            throw new IllegalArgumentException(
                    "campaignIds는 " + MAX_CAMPAIGN_IDS + "개 이하여야 합니다. size=" + campaignIds.size());
        }
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
                log.warn("event=BILLING_HOURLY_TARGET_MISSING campaignId={} businessDate={} fallback=WEIGHTED_TARGET",
                        campaignId, businessDate);
                meterRegistry.counter(FALLBACK_METRIC).increment();
            }
            long planned = BudgetPacing.plannedAmountUntil(spend.reserved(), hourlyTargets, schedule, time);
            result.add(new BudgetProgress(campaignId, spend.reserved(), plan.unitPrice(), spend.spend(),
                    planned, BudgetPacing.progressRate(spend.spend(), planned)));
        }
        return result;
    }

    // 해시가 없는 캠페인은 한꺼번에 재구성한 뒤 그 캠페인만 다시 읽는다. 그래도 없으면 예외다. 캠페인마다 DB 를 읽지 않는다
    private Map<Long, DailySpend> readSpends(Collection<Long> campaignIds, LocalDate businessDate) {
        Map<Long, DailySpend> spends = new HashMap<>(readCounters(businessDate, List.copyOf(campaignIds)));
        List<Long> missing = new ArrayList<>();
        for (Long campaignId : campaignIds) {
            if (!spends.containsKey(campaignId)) {
                missing.add(campaignId);
            }
        }
        if (missing.isEmpty()) {
            return spends;
        }
        rebuildCounters(businessDate, missing);
        spends.putAll(readCounters(businessDate, missing));
        for (Long campaignId : missing) {
            if (!spends.containsKey(campaignId)) {
                throw new IllegalStateException(
                        "재구성한 뒤에도 당일 소진 해시가 없습니다. campaignId=" + campaignId + ", businessDate=" + businessDate);
            }
        }
        return spends;
    }

    // 카운터 장애는 BudgetCounterUnavailableException으로 바꿔 던진다. 재구성 뒤 해시 없음은 이 메서드를 거치지 않는다
    private Map<Long, DailySpend> readCounters(LocalDate businessDate, List<Long> campaignIds) {
        try {
            return spendCounter.readAll(businessDate, campaignIds);
        } catch (RuntimeException e) {
            throw new BudgetCounterUnavailableException("당일 소진 카운터를 읽지 못했습니다.", e);
        }
    }

    private void rebuildCounters(LocalDate businessDate, List<Long> campaignIds) {
        try {
            rebuildService.rebuildAllIfAbsent(businessDate, campaignIds);
        } catch (RuntimeException e) {
            throw new BudgetCounterUnavailableException("당일 소진 카운터 재구성에 실패했습니다.", e);
        }
    }
}
