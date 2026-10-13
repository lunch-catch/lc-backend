package com.launchcatch.campaign.repository;

import com.launchcatch.campaign.entity.CampaignStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignStatusLogRepository extends JpaRepository<CampaignStatusLog, Long> {
}
