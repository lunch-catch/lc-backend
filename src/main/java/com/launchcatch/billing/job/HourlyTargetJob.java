package com.launchcatch.billing.job;

import com.launchcatch.billing.service.HourlyTargetPlanner;
import com.launchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 00:00 묶음의 시간대별 목표 단계다(V52 102행 (7)). 순서 값은 102행 단계 번호 × 10 이다
@Component
@Order(70)
@RequiredArgsConstructor
public class HourlyTargetJob implements DailyJob {

    static final String JOB_NAME = "BILLING_HOURLY_TARGET";

    private final HourlyTargetPlanner planner;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate) {
        planner.plan(businessDate);
    }
}
