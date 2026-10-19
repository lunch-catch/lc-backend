package com.launchcatch.adserving.impression.repository;

import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionLogId;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImpressionLogRepository extends JpaRepository<ImpressionLog, ImpressionLogId>, ImpressionLogInsert {

    /*
     * 하루 노출을 캠페인별로 한 번 훑어 판정, 사유, 슬롯을 함께 센다. 같은 행들에서 세어 축끼리의 합이 맞는다.
     * 시간대는 24칸이라 countValidByCampaignAndHourOn 이 따로 센다. 집계 조회가 한 읽기 전용 트랜잭션에서 둘을 불러 같은 스냅샷을 본다.
     * idx_imp_date_reason 의 앞 열로 그날 행만 읽는다.
     */
    @Query(value = """
            SELECT campaign_id AS campaignId,
                   SUM(CASE WHEN validity = 'VALID' THEN 1 ELSE 0 END) AS validCount,
                   SUM(CASE WHEN validity = 'VALID' AND sold_out THEN 1 ELSE 0 END) AS soldOutCount,
                   SUM(CASE WHEN invalid_reason = 'EXPIRED_OR_UNKNOWN' THEN 1 ELSE 0 END) AS expiredOrUnknownCount,
                   SUM(CASE WHEN invalid_reason = 'NOT_OWNER' THEN 1 ELSE 0 END) AS notOwnerCount,
                   SUM(CASE WHEN validity = 'VALID' AND slot_type = 'ALLOCATION' THEN 1 ELSE 0 END) AS allocationCount,
                   SUM(CASE WHEN validity = 'VALID' AND slot_type = 'RELEVANCE' THEN 1 ELSE 0 END) AS relevanceCount
              FROM impression_log
             WHERE business_date = :businessDate
             GROUP BY campaign_id
             ORDER BY campaign_id
            """, nativeQuery = true)
    List<CampaignCounts> countByCampaignOn(@Param("businessDate") LocalDate businessDate);

    /*
     * 그날 유효 노출을 캠페인과 받은 시각(created_at)의 시로 묶어 센다. 노출이 없는 시는 행이 없다.
     * created_at 은 엔티티에 매핑하지 않아 네이티브 쿼리로 쓴다.
     */
    @Query(value = """
            SELECT campaign_id AS campaignId,
                   HOUR(created_at) AS hour,
                   COUNT(*) AS validCount
              FROM impression_log
             WHERE business_date = :businessDate
               AND validity = 'VALID'
             GROUP BY campaign_id, HOUR(created_at)
            """, nativeQuery = true)
    List<HourlyCounts> countValidByCampaignAndHourOn(@Param("businessDate") LocalDate businessDate);

    // idx_imp_date_reason 의 두 열로 그날 무효 노출만 읽는다.
    @Query(value = """
            SELECT campaign_id AS campaignId,
                   member_id AS memberId,
                   SUM(CASE WHEN invalid_reason = 'EXPIRED_OR_UNKNOWN' THEN 1 ELSE 0 END) AS expiredOrUnknownCount,
                   SUM(CASE WHEN invalid_reason = 'NOT_OWNER' THEN 1 ELSE 0 END) AS notOwnerCount
              FROM impression_log
             WHERE business_date = :businessDate
               AND invalid_reason IS NOT NULL
             GROUP BY campaign_id, member_id
             ORDER BY campaign_id, member_id
            """, nativeQuery = true)
    List<MemberInvalidCounts> countInvalidByMemberOn(@Param("businessDate") LocalDate businessDate);

    /*
     * 캠페인 안에서 회원을 유효 노출이 많은 순으로 매겨 상위 topMembers 명의 합을 낸다.
     * 같은 건수의 회원끼리 순서가 갈려도 상위 합은 같다. 순서를 고정하려고 회원 ID 를 둘째 기준으로 둔다.
     */
    @Query(value = """
            SELECT ranked.campaign_id AS campaignId,
                   COUNT(*) AS distinctMemberCount,
                   SUM(CASE WHEN ranked.rn <= :topMembers THEN ranked.cnt ELSE 0 END) AS topMembersValidCount
              FROM (
                    SELECT campaign_id,
                           COUNT(*) AS cnt,
                           ROW_NUMBER() OVER (PARTITION BY campaign_id ORDER BY COUNT(*) DESC, member_id) AS rn
                      FROM impression_log
                     WHERE business_date = :businessDate
                       AND validity = 'VALID'
                     GROUP BY campaign_id, member_id
                   ) ranked
             GROUP BY ranked.campaign_id
             ORDER BY ranked.campaign_id
            """, nativeQuery = true)
    List<MemberConcentration> concentrationByCampaignOn(@Param("businessDate") LocalDate businessDate,
                                                        @Param("topMembers") int topMembers);

    interface CampaignCounts {
        long getCampaignId();
        long getValidCount();
        long getSoldOutCount();
        long getExpiredOrUnknownCount();
        long getNotOwnerCount();
        long getAllocationCount();
        long getRelevanceCount();
    }

    interface HourlyCounts {
        long getCampaignId();
        int getHour();
        long getValidCount();
    }

    interface MemberInvalidCounts {
        long getCampaignId();
        long getMemberId();
        long getExpiredOrUnknownCount();
        long getNotOwnerCount();
    }

    interface MemberConcentration {
        long getCampaignId();
        long getDistinctMemberCount();
        long getTopMembersValidCount();
    }
}
