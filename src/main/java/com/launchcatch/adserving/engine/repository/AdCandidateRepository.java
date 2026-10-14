package com.launchcatch.adserving.engine.repository;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.AdCandidateId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdCandidateRepository extends JpaRepository<AdCandidate, AdCandidateId> {

    /*
     * 그날 후보 중 서빙할 수 있는 것을 사각 범위로 1차로 거른다.
     * 범위 비교라 idx_candidate_geo 를 탄다. 원 안인지는 부르는 쪽이 거리로 다시 거른다.
     */
    @Query("""
            SELECT c FROM AdCandidate c
            WHERE c.id.businessDate = :businessDate
              AND c.latitude BETWEEN :minLatitude AND :maxLatitude
              AND c.longitude BETWEEN :minLongitude AND :maxLongitude
              AND c.campaignState = com.launchcatch.adserving.engine.entity.CandidateCampaignState.SERVABLE
              AND c.soldOutAt IS NULL
            """)
    List<AdCandidate> findServableIn(@Param("businessDate") LocalDate businessDate,
                                     @Param("minLatitude") BigDecimal minLatitude,
                                     @Param("maxLatitude") BigDecimal maxLatitude,
                                     @Param("minLongitude") BigDecimal minLongitude,
                                     @Param("maxLongitude") BigDecimal maxLongitude);
}
