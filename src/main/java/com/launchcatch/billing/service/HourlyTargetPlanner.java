package com.launchcatch.billing.service;

import com.launchcatch.billing.BudgetPacing;
import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.BudgetHourlyTarget;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.BudgetHourlyTargetRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 00:00 시간대별 목표 단계(V52 102행 (7))다. 판정까지 끝나 중단되지 않은 그날 계획마다 0~23시 시간대별 목표를 만든다.
 * 판정이 NO_POINTS 로 멈춘 캠페인과 PAUSED(OWNER) 는 계획이 중단으로 표시되어 대상에서 빠진다.
 *
 * 예약액을 설정 가중치 비율로 나누고 남는 금액은 마지막으로 가중치가 있는 시간대에 얹어 합을 예약액과 맞춘다.
 * 진행률 조회가 목표 행이 없을 때 쓰는 대체 계산(BudgetPacing.weightedTargets)을 그대로 쓴다. 식이 두 곳에 있으면
 * 목표 행이 있는 날과 없는 날의 진행률이 어긋난다.
 *
 * 목표가 이미 있는 계획은 건너뛴다. 모든 행을 한 트랜잭션에 넣으므로 계획 하나의 목표가 일부만 남는 일이 없다.
 * 건너뛸 계획을 읽은 뒤 쓰기 전에 이어받은 다른 실행이 같은 목표를 먼저 넣을 수 있어서, 쓰기는 이미 있으면 그대로 두는
 * INSERT 로 한다. saveAll 이면 그때 UNIQUE 위반으로 단계가 실패한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HourlyTargetPlanner {

    private final BudgetDailyPlanRepository planRepository;
    private final BudgetHourlyTargetRepository targetRepository;
    private final BillingScheduleProperties schedule;
    private final Clock clock;

    // 목표를 새로 만든 계획 수를 돌려준다
    @Transactional
    public int plan(LocalDate businessDate) {
        List<BudgetDailyPlan> plans = planRepository.findByBusinessDateAndPausedFalse(businessDate);
        if (plans.isEmpty()) {
            return 0;
        }
        List<Long> planIds = plans.stream().map(BudgetDailyPlan::getId).toList();
        Set<Long> done = targetRepository.findByBudgetDailyPlanIdIn(planIds).stream()
                .map(BudgetHourlyTarget::getBudgetDailyPlanId)
                .collect(Collectors.toSet());
        List<BudgetHourlyTarget> targets = new ArrayList<>();
        int planned = 0;
        for (BudgetDailyPlan plan : plans) {
            if (done.contains(plan.getId())) {
                continue;
            }
            List<Long> amounts = BudgetPacing.weightedTargets(plan.getDailyBudget(), schedule.hourlyWeights());
            for (int hour = 0; hour < amounts.size(); hour++) {
                targets.add(BudgetHourlyTarget.create(plan.getId(), hour, amounts.get(hour)));
            }
            planned++;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (BudgetHourlyTarget target : targets) {
            targetRepository.insertIfAbsent(target.getBudgetDailyPlanId(), target.getHour(), target.getTargetAmount(), now);
        }
        log.info("event=HOURLY_TARGET_DONE businessDate={} plans={}", businessDate, planned);
        return planned;
    }
}
