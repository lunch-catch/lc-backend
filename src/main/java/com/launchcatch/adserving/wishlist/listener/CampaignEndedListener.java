package com.launchcatch.adserving.wishlist.listener;

import com.launchcatch.adserving.wishlist.service.WishlistService;
import com.launchcatch.campaign.contract.CampaignEndedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/*
 * 캠페인이 끝나면 그 캠페인의 찜을 종료로 표시한다(#548).
 * 발행자와 같은 트랜잭션에서 받는다(아키텍처 설계 2.4). 표시가 실패하면 캠페인 종료도 되돌아가 둘이 어긋나지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CampaignEndedListener {

    private final WishlistService wishlistService;

    @EventListener
    public void on(CampaignEndedEvent event) {
        wishlistService.endCampaign(event.campaignId(), event.changedAt());
    }
}
