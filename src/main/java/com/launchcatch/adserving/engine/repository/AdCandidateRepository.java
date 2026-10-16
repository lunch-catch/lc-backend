package com.launchcatch.adserving.engine.repository;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.AdCandidateId;
import com.launchcatch.adserving.engine.entity.CandidateCampaignState;
import com.launchcatch.global.geo.BoundingBox;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdCandidateRepository extends JpaRepository<AdCandidate, AdCandidateId> {

    // 그날 서빙할 수 있는 후보 가운데 상자 안의 가게만 읽는다. 원 바깥은 부르는 쪽이 거리로 거른다.
    default List<AdCandidate> findServableWithin(LocalDate businessDate, BoundingBox box) {
        return findWithin(businessDate, CandidateCampaignState.SERVABLE,
                BigDecimal.valueOf(box.minLatitude()), BigDecimal.valueOf(box.maxLatitude()),
                BigDecimal.valueOf(box.minLongitude()), BigDecimal.valueOf(box.maxLongitude()));
    }

    @Query("""
            select c from AdCandidate c
            where c.id.businessDate = :businessDate
              and c.campaignState = :state
              and c.soldOutAt is null
              and c.latitude between :minLatitude and :maxLatitude
              and c.longitude between :minLongitude and :maxLongitude
            """)
    List<AdCandidate> findWithin(@Param("businessDate") LocalDate businessDate,
                                 @Param("state") CandidateCampaignState state,
                                 @Param("minLatitude") BigDecimal minLatitude,
                                 @Param("maxLatitude") BigDecimal maxLatitude,
                                 @Param("minLongitude") BigDecimal minLongitude,
                                 @Param("maxLongitude") BigDecimal maxLongitude);

    // 그날 후보가 쿠폰 소진으로 표시됐는지 본다. 소진 뒤 도착한 유효 노출은 차감하지 않는다(NOT_BILLED_SOLD_OUT).
    boolean existsByIdBusinessDateAndIdCampaignIdAndSoldOutAtIsNotNull(LocalDate businessDate, Long campaignId);

    // 그날 후보의 그 캠페인 행이다. 상태와 소진은 보지 않는다.
    Optional<AdCandidate> findByIdBusinessDateAndIdCampaignId(LocalDate businessDate, Long campaignId);
}
