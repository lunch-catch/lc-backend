package com.launchcatch.campaign.repository;

import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.entity.Campaign;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByStoreIdIn(List<Long> storeIds);

    List<Campaign> findByOwnerIdAndStatusIn(Long ownerId, List<CampaignStatus> statuses);

    boolean existsByOwnerIdAndStatusNotIn(Long ownerId, List<CampaignStatus> excludedStatuses);

    /*
     * DISTINCT owner_id 로 집계한다. hasLiveCampaign을 점주마다 부르면 관리자 목록 한 페이지
     * (최대 100행)에 최대 100번 호출이 되어 jpa-association-guideline.md AGG-1-03 을 어긴다.
     */
    @Query("select distinct c.ownerId from Campaign c where c.ownerId in :ownerIds and c.status not in :excludedStatuses")
    List<Long> findDistinctOwnerIdsWithLiveCampaign(
            @Param("ownerIds") Collection<Long> ownerIds,
            @Param("excludedStatuses") List<CampaignStatus> excludedStatuses);

    @Query("""
            select c from Campaign c
            where c.status in (
                com.launchcatch.campaign.contract.CampaignStatus.SCHEDULED,
                com.launchcatch.campaign.contract.CampaignStatus.ACTIVE
            )
            and c.startDate <= :today and c.endDate >= :today
            """)
    List<Campaign> findEligibleCampaigns(@Param("today") LocalDate today);

    /*
     * 상태 전이 때 행 잠금을 걸고 조회한다(SELECT ... FOR UPDATE). 같은 캠페인에 동시에
     * 들어오는 전이 요청(관리자 중단과 정산의 포인트 부족 판정 등)이 서로 끼어들지 못하게
     * 한 쪽이 끝날 때까지 다른 쪽을 기다리게 한다. TemplateRepository.findByIdForUpdate와
     * 같은 이유다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") Long id);

    /*
     * CampaignQueryService.findServableOn 의 조건 그대로다. billing 의 "예약이 있는지"는
     * campaign -> billing 간선이 없어 여기서 읽을 수 없다(설계 문서 2.2, 역방향은 순환이 된다).
     * ACTIVE -> PAUSED 전이만 있고(95행) PAUSED(OWNER, NO_POINTS)는 그날 ACTIVE였다가 멈춘
     * 것만 존재하므로, 오늘 집행 기간에 든 PAUSED(OWNER, NO_POINTS)는 이미 오늘 예약이 있다고
     * 봐도 된다. 그래서 campaign 자신의 컬럼만으로 조건을 표현한다.
     *
     * pageable이 상한을 건다. 전체를 불러온 뒤 크기를 재면 상한을 넘기는 순간 그 많은 행을
     * 먼저 메모리에 다 올리게 되어 상한을 둔 목적이 없어진다. 호출하는 쪽이
     * PageRequest.of(0, MAX + 1)을 넘겨 초과 여부 판정에 필요한 한 행만 더 받는다.
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
    List<Campaign> findServableOn(@Param("businessDate") LocalDate businessDate, Pageable pageable);
}
