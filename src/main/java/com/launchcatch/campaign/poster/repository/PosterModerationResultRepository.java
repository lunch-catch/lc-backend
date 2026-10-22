package com.launchcatch.campaign.poster.repository;

import com.launchcatch.campaign.poster.entity.PosterModerationResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosterModerationResultRepository extends JpaRepository<PosterModerationResult, Long> {

    List<PosterModerationResult> findByPosterIdOrderByIdDesc(Long posterId);
}
