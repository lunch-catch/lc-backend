package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.CouponIssueCondition;
import com.launchcatch.campaign.contract.CouponIssueConditionQueryService;
import com.launchcatch.campaign.repository.CampaignRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CampaignQueryService implements CouponIssueConditionQueryService {

    private final CampaignRepository campaignRepository;

    CampaignQueryService(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CouponIssueCondition> findActiveCondition(Long campaignId) {
        return campaignRepository.findActiveCondition(campaignId);
    }
}
