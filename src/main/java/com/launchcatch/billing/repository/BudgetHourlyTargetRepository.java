package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.BudgetHourlyTarget;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 시간대별 목표 저장소다. 00:00 의 시간대별 목표 단계가 만들고 진행률 조회가 읽는다
public interface BudgetHourlyTargetRepository extends JpaRepository<BudgetHourlyTarget, Long> {

    // 계획 여러 개의 목표를 한 번에 읽는다. uk_budget_hourly_target_plan_hour 를 탄다
    List<BudgetHourlyTarget> findByBudgetDailyPlanIdIn(Collection<Long> budgetDailyPlanIds);

    /*
     * 목표 한 행을 없을 때만 넣는다. 같은 계획과 시가 이미 있으면 그대로 둔다(uk_budget_hourly_target_plan_hour).
     * 이어받기로 두 실행이 겹쳐도 한쪽이 UNIQUE 위반으로 실패하지 않는다. 중복은 값을 바꾸지 않는 갱신으로
     * 흡수한다. INSERT IGNORE 는 CHECK 위반까지 경고로 낮추고 행을 조용히 버려서 쓰지 않는다(구현 계획 D-5).
     */
    @Modifying
    @Query(value = """
            INSERT INTO budget_hourly_target (budget_daily_plan_id, `hour`, target_amount, created_at, updated_at)
            VALUES (:budgetDailyPlanId, :hour, :targetAmount, :now, :now)
            ON DUPLICATE KEY UPDATE budget_hourly_target_id = budget_hourly_target_id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("budgetDailyPlanId") Long budgetDailyPlanId,
                       @Param("hour") int hour,
                       @Param("targetAmount") long targetAmount,
                       @Param("now") LocalDateTime now);
}
