package com.launchcatch.campaign.service;

import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.coupon.service.CouponIssuedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignBudgetService {

    private final CampaignRepository campaignRepository;

    public CampaignBudgetService(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    // 쿠폰이 발급되면 그만큼 예산을 깎는다
    @Transactional
    public void drain(CouponIssuedEvent event) {
        campaignRepository.findById(event.campaignId()).orElseThrow();
    }
}
