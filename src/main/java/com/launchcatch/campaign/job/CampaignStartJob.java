package com.launchcatch.campaign.job;

import com.launchcatch.campaign.service.CampaignDailyTransitionService;
import com.launchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 00:00 묶음의 둘째 단계다(요구사항명세서 102행 (2), #569). 집행 시작일이 된 SCHEDULED 캠페인을 ACTIVE 로 바꾸고,
 * 같은 가게의 기존 ACTIVE 캠페인은 ENDED 로 바꾼다. 순서 값은 102행 단계 번호 × 10 이다.
 */
@Component
@Order(20)
@RequiredArgsConstructor
public class CampaignStartJob implements DailyJob {

    static final String JOB_NAME = "CAMPAIGN_START_SCHEDULED";

    private final CampaignDailyTransitionService transitions;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate) {
        transitions.startScheduled(businessDate);
    }
}
