package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.CampaignStatsQueryService;
import com.launchcatch.campaign.repository.CampaignStatusLogRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CampaignStatsQueryServiceImpl implements CampaignStatsQueryService {

    private final CampaignStatusLogRepository campaignStatusLogRepository;

    @Override
    public long activeCampaignCount(LocalDate businessDate) {
        return campaignStatusLogRepository.countActiveAsOf(businessDate.plusDays(1).atStartOfDay());
    }
}
