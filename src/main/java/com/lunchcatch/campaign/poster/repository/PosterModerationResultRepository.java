package com.lunchcatch.campaign.poster.repository;

import com.lunchcatch.campaign.poster.entity.PosterModerationResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosterModerationResultRepository extends JpaRepository<PosterModerationResult, Long> {

    List<PosterModerationResult> findByPosterIdOrderByIdDesc(Long posterId);
}
