package com.launchcatch.campaign.service;

import org.springframework.stereotype.Service;

@Service
public class CampaignLookupService {

    public boolean hasActiveCampaign(Long storeId) {
        return storeId != null && storeId % 2 == 0;
    }
}
