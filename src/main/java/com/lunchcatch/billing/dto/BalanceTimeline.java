package com.lunchcatch.billing.dto;

import java.time.LocalDateTime;
import java.util.List;

/*
 * 한 점주의 잔액이 시간에 따라 어떻게 쌓였는지 알려 준다.
 * 잔액은 저장하지 않고 원장 합으로 계산하므로, 변동 전후 잔액은 기간 시작 잔액에서 출발해
 * (기록 시각, 원장 ID) 오름차순으로 누적해서 구한다.
 *
 * steps 는 그 순서로 정렬되어 있어야 한다. 한 줄의 변동 전 잔액은 그 줄보다 앞선 줄들의 합이라
 * DEDUCT 처럼 잔액을 바꾸지 않는 줄도 직전 잔액을 그대로 얻는다.
 */
public final class BalanceTimeline {

    private final long[] cumulative;
    private final List<BalanceStep> steps;

    public BalanceTimeline(long startBalance, List<BalanceStep> steps) {
        this.steps = steps;
        this.cumulative = new long[steps.size() + 1];
        cumulative[0] = startBalance;
        for (int i = 0; i < steps.size(); i++) {
            cumulative[i + 1] = cumulative[i] + steps.get(i).amount();
        }
    }

    // (createdAt, id) 위치의 줄이 반영되기 직전의 잔액이다
    public long before(LocalDateTime createdAt, Long id) {
        int low = 0;
        int high = steps.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (isBefore(steps.get(mid), createdAt, id)) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return cumulative[low];
    }

    private boolean isBefore(BalanceStep step, LocalDateTime createdAt, Long id) {
        int byTime = step.createdAt().compareTo(createdAt);
        return byTime < 0 || (byTime == 0 && step.id() < id);
    }
}
