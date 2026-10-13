package com.launchcatch.billing.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.dto.PlanSnapshotKey;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 그날 계획에서 하루 동안 바뀌지 않는 점주와 단가를 영업일 단위로 캐시해 돌려준다.
 *
 * 노출마다 계획 행을 읽으면 핫패스가 DB 에 묶여서 두 값만 메모리에 둔다. 예약액과 중단 여부는
 * 장중에 바뀌므로(D-7) 캐시하지 않고 Valkey 해시가 미러링한다.
 *
 * 계획 행이 없으면 예외다(E-4). 없는 결과는 캐시하지 않아서 행이 생기면 다음 호출이 읽는다.
 */
@Service
@RequiredArgsConstructor
public class DailyPlanSnapshotService {

    private final BudgetDailyPlanRepository planRepository;
    private final Cache<PlanSnapshotKey, PlanSnapshot> cache;

    public PlanSnapshot get(LocalDate businessDate, Long campaignId) {
        return cache.get(new PlanSnapshotKey(businessDate, campaignId), key -> load(key));
    }

    private PlanSnapshot load(PlanSnapshotKey key) {
        return planRepository.findByCampaignIdAndBusinessDate(key.campaignId(), key.businessDate())
                .map(plan -> new PlanSnapshot(plan.getOwnerId(), plan.getUnitPrice()))
                .orElseThrow(() -> new IllegalStateException(
                        "그날 예산 계획이 없습니다. campaignId=" + key.campaignId() + ", businessDate=" + key.businessDate()));
    }
}
