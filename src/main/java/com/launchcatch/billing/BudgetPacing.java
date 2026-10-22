package com.launchcatch.billing;

import com.launchcatch.billing.config.BillingScheduleProperties;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/*
 * 시각까지의 계획 소진액과 진행률을 계산한다. 값만 받는 순수 계산이라 DB, Valkey, Clock 에 기대지 않는다.
 *
 * 서빙은 하루 종일이고 0시부터 23시까지 시간대 목표가 하나씩 이어진다. 지난 시간대의 목표를 모두 더하고,
 * 지금 시간대는 목표를 경과 분 / 60 만큼 비례해 더한다. 23:59 에는 23시 목표의 59분 몫까지이고 하루가 끝나야
 * 예약액 전체가 된다.
 *
 * 시간대 목표 행이 없으면 예약액을 가중치 비율로 그 자리에서 나눈다(E-7). 나누어떨어지지 않는 몫은 내림하고,
 * 합이 예약액과 맞도록 남는 금액을 마지막으로 가중치가 있는 시간대에 더한다.
 * 분 단위 비례에서 생기는 소수도 내림이다.
 */
public final class BudgetPacing {

    private BudgetPacing() {
    }

    /*
     * at 까지의 계획 소진액이다.
     * hourlyTargets 는 시(0~23)별 목표이고 비어 있으면 가중치로 계산한 목표를 쓴다.
     */
    public static long plannedAmountUntil(long reservedAmount, Map<Integer, Long> hourlyTargets,
                                          BillingScheduleProperties schedule, LocalTime at) {
        List<Long> targets = hourlyTargets.isEmpty()
                ? weightedTargets(reservedAmount, schedule.hourlyWeights())
                : targetsFrom(hourlyTargets);
        long planned = 0L;
        for (int hour = 0; hour < at.getHour(); hour++) {
            planned += targets.get(hour);
        }
        return planned + targets.get(at.getHour()) * at.getMinute() / 60;
    }

    // 계획이 0 이면 0 이다. 1.0 으로 자르지 않는다
    public static double progressRate(long todaySpend, long plannedAmountUntil) {
        if (plannedAmountUntil <= 0) {
            return 0.0;
        }
        return (double) todaySpend / plannedAmountUntil;
    }

    /*
     * 예약액을 가중치 비율로 나눈 시간대별 목표다. 남는 금액은 마지막으로 가중치가 있는 시간대에 더한다.
     * 목표 행이 없을 때의 대체 계산이라 진행률 조회가 같은 값을 쓰도록 공개한다.
     */
    public static List<Long> weightedTargets(long reservedAmount, List<Integer> weights) {
        long totalWeight = weights.stream().mapToLong(Integer::longValue).sum();
        Long[] targets = new Long[weights.size()];
        long assigned = 0L;
        int lastWeighted = 0;
        for (int i = 0; i < targets.length; i++) {
            targets[i] = reservedAmount * weights.get(i) / totalWeight;
            assigned += targets[i];
            if (weights.get(i) > 0) {
                lastWeighted = i;
            }
        }
        targets[lastWeighted] += reservedAmount - assigned;
        return List.of(targets);
    }

    private static List<Long> targetsFrom(Map<Integer, Long> hourlyTargets) {
        Long[] targets = new Long[BillingScheduleProperties.HOURS];
        for (int hour = 0; hour < targets.length; hour++) {
            targets[hour] = hourlyTargets.getOrDefault(hour, 0L);
        }
        return List.of(targets);
    }
}
