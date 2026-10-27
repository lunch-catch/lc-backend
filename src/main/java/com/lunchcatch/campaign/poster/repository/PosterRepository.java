package com.lunchcatch.campaign.poster.repository;

import com.lunchcatch.campaign.poster.entity.Poster;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosterRepository extends JpaRepository<Poster, Long> {

    Optional<Poster> findByCampaignId(Long campaignId);
}
