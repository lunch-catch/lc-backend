package com.launchcatch.coupon.service;

import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponIssueService {

    private final CampaignRepository campaignRepository;

    public CouponIssueService(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    // 예산이 남아 있으면 발급한다
    @Transactional
    public boolean issue(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId).orElseThrow();
        return campaign.getDailyBudget() > 0;
    }
}
