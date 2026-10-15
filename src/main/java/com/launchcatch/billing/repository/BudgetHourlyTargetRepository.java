package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.BudgetHourlyTarget;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

// 시간대별 목표 저장소다. 00:00 의 시간대별 목표 단계가 만들고 진행률 조회가 읽는다
public interface BudgetHourlyTargetRepository extends JpaRepository<BudgetHourlyTarget, Long> {

    // 계획 여러 개의 목표를 한 번에 읽는다. uk_budget_hourly_target_plan_hour 를 탄다
    List<BudgetHourlyTarget> findByBudgetDailyPlanIdIn(Collection<Long> budgetDailyPlanIds);
}
