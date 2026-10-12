package com.launchcatch.campaign.repository;

import com.launchcatch.campaign.entity.Campaign;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByStoreIdIn(List<Long> storeIds);

    /*
     * CampaignQueryService.findServableOn 의 조건 그대로다. billing 의 "예약이 있는지"는
     * campaign -> billing 간선이 없어 여기서 읽을 수 없다(설계 문서 2.2, 역방향은 순환이 된다).
     * ACTIVE -> PAUSED 전이만 있고(95행) PAUSED(OWNER, NO_POINTS)는 그날 ACTIVE였다가 멈춘
     * 것만 존재하므로, 오늘 집행 기간에 든 PAUSED(OWNER, NO_POINTS)는 이미 오늘 예약이 있다고
     * 봐도 된다. 그래서 campaign 자신의 컬럼만으로 조건을 표현한다.
     */
    @Query("""
            select c from Campaign c
            where c.startDate <= :businessDate and c.endDate >= :businessDate
            and (
                c.status = com.launchcatch.campaign.contract.CampaignStatus.ACTIVE
                or (
                    c.status = com.launchcatch.campaign.contract.CampaignStatus.PAUSED
                    and c.pausedReason in (
                        com.launchcatch.campaign.contract.PauseReason.OWNER,
                        com.launchcatch.campaign.contract.PauseReason.NO_POINTS
                    )
                )
            )
            """)
    List<Campaign> findServableOn(@Param("businessDate") LocalDate businessDate);
}
