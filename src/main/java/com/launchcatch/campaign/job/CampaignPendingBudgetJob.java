package com.launchcatch.campaign.job;

import com.launchcatch.campaign.service.CampaignDailyTransitionService;
import com.launchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 00:00 묶음에서 ACTIVE 캠페인이 예약해 둔 하루 예산 변경을 적용한다(#840). 정산의 일일 예약(DailyReservationJob, 50)이
 * 새 하루 예산으로 예약하도록 그보다 앞에 둔다. 캠페인 전환(10, 20), 가게 요약(30) 뒤이고 정산 첫 단계(40) 앞이다.
 */
@Component
@Order(35)
@RequiredArgsConstructor
public class CampaignPendingBudgetJob implements DailyJob {

    static final String JOB_NAME = "CAMPAIGN_APPLY_PENDING_BUDGET";

    private final CampaignDailyTransitionService transitions;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate) {
        transitions.applyPendingDailyBudgets(businessDate);
    }
}
