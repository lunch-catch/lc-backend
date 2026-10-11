package com.launchcatch.billing.listener;

import com.launchcatch.billing.service.CampaignStatusService;
import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/*
 * 캠페인의 상태 변경을 받는다(I-07). 발행자와 같은 트랜잭션에서 돈다.
 * 중단 표시가 상태 변경과 함께 커밋되어야, 중단이 커밋된 뒤에 과금되는 틈이 생기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CampaignStatusChangedListener {

    private final CampaignStatusService campaignStatusService;

    @EventListener
    public void on(CampaignStatusChangedEvent event) {
        campaignStatusService.apply(event);
    }
}
