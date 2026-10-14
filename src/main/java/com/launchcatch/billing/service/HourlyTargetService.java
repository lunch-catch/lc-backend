package com.launchcatch.billing.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.launchcatch.billing.entity.BudgetHourlyTarget;
import com.launchcatch.billing.repository.BudgetHourlyTargetRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 계획 행별 시간대별 목표를 캐시해 돌려준다. 목표 행은 00:00 예약이 만든 뒤 하루 동안 바뀌지 않는다.
 *
 * 캐시에 없는 계획만 한 번의 쿼리로 읽는다. 목표 행이 없는 계획은 결과에도 캐시에도 넣지 않는다.
 * 행이 나중에 생길 수 있고, 없는 상태는 부른 쪽이 가중치 대체 계산으로 다룬다.
 */
@Service
@RequiredArgsConstructor
public class HourlyTargetService {

    private final BudgetHourlyTargetRepository targetRepository;
    private final Cache<Long, Map<Integer, Long>> cache;

    // 계획 ID별로 시(hour)에서 목표 금액으로 가는 맵을 돌려준다. 목표 행이 없는 계획은 빠진다
    public Map<Long, Map<Integer, Long>> findAll(Collection<Long> planIds) {
        Map<Long, Map<Integer, Long>> result = new HashMap<>();
        List<Long> misses = new ArrayList<>();
        for (Long planId : planIds) {
            Map<Integer, Long> hit = cache.getIfPresent(planId);
            if (hit != null) {
                result.put(planId, hit);
            } else {
                misses.add(planId);
            }
        }
        if (misses.isEmpty()) {
            return result;
        }
        Map<Long, Map<Integer, Long>> loaded = new HashMap<>();
        for (BudgetHourlyTarget target : targetRepository.findByBudgetDailyPlanIdIn(misses)) {
            loaded.computeIfAbsent(target.getBudgetDailyPlanId(), id -> new HashMap<>())
                    .put((int) target.getHour(), target.getTargetAmount());
        }
        loaded.replaceAll((planId, targets) -> Map.copyOf(targets));
        cache.putAll(loaded);
        result.putAll(loaded);
        return result;
    }
}
