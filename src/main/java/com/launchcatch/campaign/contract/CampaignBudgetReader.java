package com.launchcatch.campaign.contract;

import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import org.springframework.stereotype.Component;

// 다른 도메인이 캠페인 예산을 읽을 때 쓴다
@Component
public class CampaignBudgetReader {

    private final CampaignRepository campaignRepository;

    public CampaignBudgetReader(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    public Campaign read(Long campaignId) {
        return campaignRepository.findById(campaignId).orElseThrow();
    }
}
