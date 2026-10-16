package com.launchcatch.campaign.repository;

import com.launchcatch.campaign.entity.CampaignStatusLog;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignStatusLogRepository extends JpaRepository<CampaignStatusLog, Long> {

    /*
     * endExclusive(다음 날 00:00 KST) 이전 시각 기준 ACTIVE였던 캠페인 수를 센다(#354).
     * 캠페인마다 그 시각 이전의 마지막 전이 행이 그 시각의 상태를 결정한다. 전이 행이 아직 없으면
     * 최초 상태(DRAFT, 로그 없음)라 세지 않는다.
     */
    @Query(value = """
            select count(*)
            from (
                select to_status,
                       row_number() over (
                           partition by campaign_id
                           order by created_at desc, campaign_status_log_id desc
                       ) as rn
                from campaign_status_log
                where created_at < :endExclusive
            ) latest
            where rn = 1 and to_status = 'ACTIVE'
            """, nativeQuery = true)
    long countActiveAsOf(@Param("endExclusive") LocalDateTime endExclusive);
}
