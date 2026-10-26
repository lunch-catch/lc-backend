package com.launchcatch.billing.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.dto.PlanSnapshotKey;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 그날 계획에서 하루 동안 바뀌지 않는 계획 ID, 점주, 단가를 영업일 단위로 캐시해 돌려준다.
 *
 * 노출마다, 진행률 조회마다 계획 행을 읽으면 핫패스가 DB 에 묶여서 이 값만 메모리에 둔다. 예약액과 중단 여부는
 * 장중에 바뀌므로 캐시하지 않고 Valkey 해시가 미러링한다.
 *
 * 계획 행이 없는 경우는 캐시하지 않는다. find 는 비어 있고 findAll 은 결과에서 뺀다.
 * 행이 생기면 다음 호출이 읽는다.
 */
@Service
@RequiredArgsConstructor
public class DailyPlanSnapshotService {

    private final BudgetDailyPlanRepository planRepository;
    private final Cache<PlanSnapshotKey, PlanSnapshot> cache;

    // 노출 차감이 쓴다. 계획 행이 없으면 비어 있다. Caffeine 은 null 을 돌려준 적재를 캐시하지 않는다
    public Optional<PlanSnapshot> find(LocalDate businessDate, Long campaignId) {
        return Optional.ofNullable(cache.get(new PlanSnapshotKey(businessDate, campaignId), key -> load(key)));
    }

    /*
     * 진행률 조회가 쓴다. 캠페인 여러 개를 한 번에 받고 캐시에 없는 캠페인만 한 번의 쿼리로 읽는다.
     * 계획 행이 없는 캠페인은 결과에서 빠진다. 결과는 요청한 순서를 지킨다.
     */
    public Map<Long, PlanSnapshot> findAll(LocalDate businessDate, Collection<Long> campaignIds) {
        Map<Long, PlanSnapshot> found = new HashMap<>();
        List<Long> misses = new ArrayList<>();
        for (Long campaignId : campaignIds) {
            PlanSnapshot hit = cache.getIfPresent(new PlanSnapshotKey(businessDate, campaignId));
            if (hit != null) {
                found.put(campaignId, hit);
            } else {
                misses.add(campaignId);
            }
        }
        if (!misses.isEmpty()) {
            for (BudgetDailyPlan plan : planRepository.findByBusinessDateAndCampaignIdIn(businessDate, misses)) {
                PlanSnapshot snapshot = snapshotOf(plan);
                cache.put(new PlanSnapshotKey(businessDate, plan.getCampaignId()), snapshot);
                found.put(plan.getCampaignId(), snapshot);
            }
        }
        Map<Long, PlanSnapshot> ordered = new LinkedHashMap<>();
        for (Long campaignId : campaignIds) {
            if (found.containsKey(campaignId)) {
                ordered.put(campaignId, found.get(campaignId));
            }
        }
        return ordered;
    }

    private PlanSnapshot load(PlanSnapshotKey key) {
        return planRepository.findByCampaignIdAndBusinessDate(key.campaignId(), key.businessDate())
                .map(plan -> snapshotOf(plan))
                .orElse(null);
    }

    private static PlanSnapshot snapshotOf(BudgetDailyPlan plan) {
        return new PlanSnapshot(plan.getOwnerId(), plan.getUnitPrice());
    }
}
