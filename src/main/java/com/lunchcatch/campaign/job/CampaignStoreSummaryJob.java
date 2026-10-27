package com.lunchcatch.campaign.job;

import com.lunchcatch.campaign.service.CampaignStoreSummaryService;
import com.lunchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 00:00 묶음에서 캠페인 전환(10, 20) 뒤에 진행 중인 캠페인의 가게 요약을 새 영업일로 만든다(#838).
 * 전환 뒤에 두어야 방금 켜진 캠페인은 들어가고 방금 끝난 캠페인은 빠진다. 정산 단계(40~)보다 앞이다.
 */
@Component
@Order(30)
@RequiredArgsConstructor
public class CampaignStoreSummaryJob implements DailyJob {

    static final String JOB_NAME = "CAMPAIGN_STORE_SUMMARY";

    private final CampaignStoreSummaryService storeSummaries;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate) {
        storeSummaries.prepare(businessDate);
    }
}
