package com.launchcatch.coupon.listener;

import com.launchcatch.campaign.contract.CampaignEndedEvent;
import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import com.launchcatch.coupon.service.CouponEventStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 발행자와 같은 트랜잭션에서 쿠폰 회차를 바꾸고 DB 쓰기 실패를 발행자에게 전파한다.
@Component
@RequiredArgsConstructor
public class CampaignEventListener {

    private final CouponEventStateService couponEventStateService;

    @EventListener
    public void on(CampaignStatusChangedEvent event) {
        couponEventStateService.changeCampaignStatus(event.campaignId(), event.businessDate(), event.servable(),
                event.changedAt());
    }

    @EventListener
    public void on(CampaignEndedEvent event) {
        couponEventStateService.endCampaign(event.campaignId(), event.changedAt());
    }
}
