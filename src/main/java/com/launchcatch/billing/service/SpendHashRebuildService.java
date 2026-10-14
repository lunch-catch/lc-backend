package com.launchcatch.billing.service;

import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.repository.PointLedgerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import com.launchcatch.billing.dto.CampaignSpend;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 당일 소진 해시가 없을 때 DB 정본으로 다시 만든다. 노출 차감과 진행률 조회가 같은 재구성을 쓴다.
 *
 * 예약액, 중단 여부, 상태 변경 시각은 그날 계획 행에서, 소진은 원장의 그날 DEDUCT 합에서 읽는다.
 * 해시가 이미 있으면 덮어쓰지 않는다. 있는 해시는 그 뒤의 차감이 늘린 소진을 담고 있을 수 있다(E-6).
 *
 * 해시를 지우는 사건(Valkey 재시작, 만료, 수동 삭제)이 있었다는 신호이므로 만들 때마다 지표를 올린다.
 *
 * 계획 행은 잠금 읽기(FOR SHARE)로 먼저 읽고, 원장 합은 그 뒤에 읽는다. 잠금은 트랜잭션이 끝날 때까지 가서
 * 일시중지의 UPDATE 가 해시가 만들어진 뒤로 밀린다. 그래서 읽은 시점과 해시를 쓰는 시점 사이에 커밋된 중단이
 * 해시에 빠지지 않는다. 잠금이 트랜잭션 끝까지 가려면 재구성이 트랜잭션 안에서 돌아야 해서 두 메서드에 @Transactional 을 둔다.
 */
@Service
@RequiredArgsConstructor
public class SpendHashRebuildService {

    static final String REBUILD_METRIC = "billing_spend_hash_rebuild_total";

    private final BudgetDailyPlanRepository planRepository;
    private final PointLedgerRepository ledgerRepository;
    private final DailySpendCounter spendCounter;
    private final MeterRegistry meterRegistry;

    // 해시를 만들었으면 true, 이미 있어서 그대로 두었으면 false 다. 계획 행이 없으면 예외다(E-4)
    @Transactional
    public boolean rebuildIfAbsent(LocalDate businessDate, Long campaignId) {
        BudgetDailyPlan plan = planRepository.findForShare(campaignId, businessDate)
                .orElseThrow(() -> new IllegalStateException(
                        "그날 예산 계획이 없습니다. campaignId=" + campaignId + ", businessDate=" + businessDate));
        long spend = ledgerRepository.sumDeductForCampaignOn(campaignId, businessDate);
        boolean created = spendCounter.rebuildIfAbsent(
                businessDate, campaignId, plan.getDailyBudget(), spend, plan.isPaused(), plan.getStatusChangedAt());
        if (created) {
            meterRegistry.counter(REBUILD_METRIC).increment();
        }
        return created;
    }

    /*
     * 캠페인 여러 개를 한 번에 재구성한다. 계획 행은 한 번의 IN 조회로, 원장 합은 한 번의 그룹 조회로 읽는다.
     * 해시를 쓰는 Valkey 호출만 캠페인마다 나간다. 만든 해시 수를 돌려준다.
     * 계획 행이 없는 캠페인이 하나라도 있으면 예외다(E-4). 쓰기 전에 확인해서 일부만 만들어지지 않는다.
     * 계획 행은 캠페인 ID 순으로 한 번에 잠가서 읽는다.
     */
    @Transactional
    public int rebuildAllIfAbsent(LocalDate businessDate, Collection<Long> campaignIds) {
        Map<Long, BudgetDailyPlan> plans = new HashMap<>();
        for (BudgetDailyPlan plan : planRepository.findAllForShare(businessDate, campaignIds)) {
            plans.put(plan.getCampaignId(), plan);
        }
        for (Long campaignId : campaignIds) {
            if (!plans.containsKey(campaignId)) {
                throw new IllegalStateException(
                        "그날 예산 계획이 없습니다. campaignId=" + campaignId + ", businessDate=" + businessDate);
            }
        }
        Map<Long, Long> spends = new HashMap<>();
        for (CampaignSpend spend : ledgerRepository.sumDeductForCampaignsOn(campaignIds, businessDate)) {
            spends.put(spend.campaignId(), spend.spend());
        }
        int createdCount = 0;
        for (Long campaignId : campaignIds) {
            BudgetDailyPlan plan = plans.get(campaignId);
            boolean created = spendCounter.rebuildIfAbsent(businessDate, campaignId, plan.getDailyBudget(),
                    spends.getOrDefault(campaignId, 0L), plan.isPaused(), plan.getStatusChangedAt());
            if (created) {
                meterRegistry.counter(REBUILD_METRIC).increment();
                createdCount++;
            }
        }
        return createdCount;
    }
}
