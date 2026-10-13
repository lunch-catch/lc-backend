package com.launchcatch.billing.service;

import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.repository.PointLedgerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 당일 소진 해시가 없을 때 DB 정본으로 다시 만든다. 노출 차감과 진행률 조회가 같은 재구성을 쓴다.
 *
 * 예약액, 중단 여부, 상태 변경 시각은 그날 계획 행에서, 소진은 원장의 그날 DEDUCT 합에서 읽는다.
 * 해시가 이미 있으면 덮어쓰지 않는다. 있는 해시는 그 뒤의 차감이 늘린 소진을 담고 있을 수 있다(E-6).
 *
 * 해시를 지우는 사건(Valkey 재시작, 만료, 수동 삭제)이 있었다는 신호이므로 만들 때마다 지표를 올린다.
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
    public boolean rebuildIfAbsent(LocalDate businessDate, Long campaignId) {
        BudgetDailyPlan plan = planRepository.findByCampaignIdAndBusinessDate(campaignId, businessDate)
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
}
