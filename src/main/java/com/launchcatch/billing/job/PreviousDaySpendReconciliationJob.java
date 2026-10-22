package com.launchcatch.billing.job;

import com.launchcatch.billing.service.SpendReconciliationService;
import com.launchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 00:00 묶음의 전일 소진 대사 단계다(V52 102행 (4)). 순서 값은 102행 단계 번호 × 10 이다
@Component
@Order(40)
@RequiredArgsConstructor
public class PreviousDaySpendReconciliationJob implements DailyJob {

    static final String JOB_NAME = "BILLING_SPEND_RECON_PREV";

    private final SpendReconciliationService reconciliation;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate) {
        reconciliation.reconcilePreviousDay(businessDate, JOB_NAME);
    }
}
