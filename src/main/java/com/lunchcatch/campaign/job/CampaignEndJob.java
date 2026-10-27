package com.lunchcatch.campaign.job;

import com.lunchcatch.campaign.service.CampaignDailyTransitionService;
import com.lunchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 00:00 묶음의 첫 단계다(요구사항명세서 102행 (1), #569). 집행 기간이 끝난 ACTIVE, PAUSED 캠페인을 ENDED 로 바꾼다.
 * 순서 값은 102행 단계 번호 × 10 이다. 정산 단계가 40~70 을 쓴다.
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class CampaignEndJob implements DailyJob {

    static final String JOB_NAME = "CAMPAIGN_END_EXPIRED";

    private final CampaignDailyTransitionService transitions;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate) {
        transitions.endExpired(businessDate);
    }
}
