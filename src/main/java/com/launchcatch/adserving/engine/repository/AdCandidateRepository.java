package com.launchcatch.adserving.engine.repository;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.AdCandidateId;
import com.launchcatch.adserving.engine.entity.CandidateCampaignState;
import com.launchcatch.global.geo.BoundingBox;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdCandidateRepository extends JpaRepository<AdCandidate, AdCandidateId>, AdCandidateLoad {

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

    boolean existsByIdBusinessDateAndIdCampaignId(LocalDate businessDate, Long campaignId);

    // 그날 후보의 그 캠페인 행이다. 상태와 소진은 보지 않는다.
    Optional<AdCandidate> findByIdBusinessDateAndIdCampaignId(LocalDate businessDate, Long campaignId);

    // 그날 후보를 모두 읽는다. 서버 메모리의 그날 후보(CandidateCache)를 채운다.
    List<AdCandidate> findByIdBusinessDate(LocalDate businessDate);

    /*
     * 그날 후보 가운데 그 캠페인들의 행이다.
     * 상태와 소진은 보지 않는다.
     */
    List<AdCandidate> findByIdBusinessDateAndIdCampaignIdIn(LocalDate businessDate, Collection<Long> campaignIds);

    // 아직 소진되지 않은 그날 후보에만 쓴다. 다시 와도 처음 소진 시각이 남는다(#163)
    @Modifying
    @Query(value = """
            UPDATE ad_candidate
               SET sold_out_at = :soldOutAt, updated_at = :now
             WHERE business_date = :businessDate
               AND campaign_id = :campaignId
               AND sold_out_at IS NULL
            """, nativeQuery = true)
    int markSoldOut(@Param("businessDate") LocalDate businessDate, @Param("campaignId") Long campaignId,
            @Param("soldOutAt") LocalDateTime soldOutAt, @Param("now") LocalDateTime now);

    /*
     * 그날 후보의 서빙 상태를 바꾼다.
     * 가진 전이 시각보다 늦은 통보만 반영해 다시 오거나 순서가 바뀌어도 결과가 같다(#565).
     */
    @Modifying
    @Query(value = """
            UPDATE ad_candidate
               SET campaign_state = :state, state_changed_at = :changedAt, updated_at = :now
             WHERE business_date = :businessDate
               AND campaign_id = :campaignId
               AND (state_changed_at IS NULL OR state_changed_at < :changedAt)
            """, nativeQuery = true)
    int changeState(@Param("businessDate") LocalDate businessDate, @Param("campaignId") Long campaignId,
            @Param("state") String state, @Param("changedAt") LocalDateTime changedAt, @Param("now") LocalDateTime now);

    // 그날 후보에 있는 캠페인 ID 다. 다시 돈 적재의 재조회가 이번 적재가 넣지 않은 행까지 보려고 읽는다(#782)
    @Query("select c.id.campaignId from AdCandidate c where c.id.businessDate = :businessDate")
    List<Long> findCampaignIdsByBusinessDate(@Param("businessDate") LocalDate businessDate);

    // 보관 기간이 지난 영업일의 후보를 지운다(#582)
    @Modifying
    @Query("delete from AdCandidate c where c.id.businessDate < :before")
    int deleteByBusinessDateBefore(@Param("before") LocalDate before);
}
