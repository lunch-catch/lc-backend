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

    /*
     * 캠페인 등록 1단계(쿠폰 조건, #244)가 가게당 DRAFT 1개를 찾아 덮어쓸 때 쓴다. 잠금 없이
     * 조회하면 같은 가게로 거의 동시에 들어온 두 덮어쓰기 요청이 같은 행을 각자 메모리에서
     * 고친 뒤 커밋해, 나중에 커밋한 쪽이 먼저 커밋한 쪽의 변경을 조용히 지운다("lost
     * update"). uk_campaign_draft_store UNIQUE 제약은 "새로 만드는 두 요청"의 경쟁만 막고
     * 이 경쟁은 못 막는다. findByIdForUpdate와 같은 이유로 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Campaign> findByStoreIdAndStatus(Long storeId, CampaignStatus status);

    /*
     * 00:00 배치(#838)가 새 영업일의 가게 요약을 만들 ACTIVE 캠페인을 id 순으로 청크 단위로 읽는다. lastId 보다 큰
     * 것만 읽어 다음 청크를 이어가고(offset 이 없어 뒤로 갈수록 느려지지 않는다), 엔티티가 아닌 행으로 읽어 영속성
     * 컨텍스트에 쌓이지 않게 한다. 행 잠금은 걸지 않는다.
     */
    @Query("""
            select new com.launchcatch.campaign.repository.CampaignSummaryRow(
                c.storeId, c.id, c.status, c.discountTargetType, c.discountType, c.discountValue,
                c.targetMenuId, c.usableStartTime, c.usableEndTime)
            from Campaign c
            where c.status = com.launchcatch.campaign.contract.CampaignStatus.ACTIVE
            and c.id > :lastId
            order by c.id
            """)
    List<CampaignSummaryRow> findActiveSummaryRowsAfter(@Param("lastId") Long lastId, Pageable pageable);

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
     * 00:00 배치(#569)가 집행 기간이 끝난 캠페인을 찾는다. 집행 종료일이 영업일보다 앞이면(어제까지가 집행 기간이었으면)
     * ACTIVE, PAUSED 모두 종료 대상이다. 전이는 캠페인마다 행 잠금 아래 따로 하므로 여기서는 id 만 읽는다.
     */
    @Query("""
            select c.id from Campaign c
            where c.status in (
                com.launchcatch.campaign.contract.CampaignStatus.ACTIVE,
                com.launchcatch.campaign.contract.CampaignStatus.PAUSED
            )
            and c.endDate < :businessDate
            order by c.id
            """)
    List<Long> findIdsToEnd(@Param("businessDate") LocalDate businessDate);

    /*
     * 00:00 배치(#569)가 집행 시작일이 된 SCHEDULED 캠페인을 찾는다. 시작일이 영업일 이하이면 대상이라 배치가 하루
     * 빠졌어도 다음 실행이 이어서 켠다. 집행 기간이 이미 지난 SCHEDULED 는 켜지 않는다.
     */
    @Query("""
            select c from Campaign c
            where c.status = com.launchcatch.campaign.contract.CampaignStatus.SCHEDULED
            and c.startDate <= :businessDate and c.endDate >= :businessDate
            order by c.id
            """)
    List<Campaign> findToStart(@Param("businessDate") LocalDate businessDate);

    // 새 캠페인을 켜기 전에 같은 가게에서 아직 ACTIVE 인 다른 캠페인을 찾는다(#569). 가게에 ACTIVE 가 둘 생기지 않게 한다.
    @Query("""
            select c.id from Campaign c
            where c.storeId = :storeId
            and c.status = com.launchcatch.campaign.contract.CampaignStatus.ACTIVE
            and c.id <> :campaignId
            order by c.id
            """)
    List<Long> findOtherActiveIdsByStoreId(@Param("storeId") Long storeId, @Param("campaignId") Long campaignId);

    // 00:00 배치(#840)가 하루 예산 변경을 적용할 ACTIVE 캠페인의 id 다. 적용은 캠페인마다 행 잠금 아래에서 다시 확인한다.
    @Query("""
            select c.id from Campaign c
            where c.status = com.launchcatch.campaign.contract.CampaignStatus.ACTIVE
            and c.pendingDailyBudget is not null
            order by c.id
            """)
    List<Long> findIdsWithPendingDailyBudget();

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
     * ACTIVE -> PAUSED 전이만 있고(FR-092) PAUSED(OWNER, NO_POINTS)는 그날 ACTIVE였다가 멈춘
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

    /*
     * 점주의 캠페인 목록 조회(#341) 커서 페이징이다. campaignId는 auto-increment라 생성
     * 순서와 일치해서 TemplateVersionRepository.findPage와 같이 단일 커서 값으로 충분하다.
     *
     * owner_id 단일 인덱스(idx_campaign_owner_id)만으로는 커서 조건(campaign_id < ?)이
     * 더해지면 MySQL이 index_merge(idx_campaign_owner_id, PRIMARY) + filesort로 빠진다(실측
     * 확인, V13 마이그레이션 주석). JPQL은 테이블 힌트를 낼 수 없어 네이티브 쿼리로 바꾸고
     * FORCE INDEX로 복합 인덱스(idx_campaign_owner_cursor, V13)를 명시한다. LIMIT도 Pageable이
     * 아니라 파라미터로 직접 받는다. Spring Data의 네이티브 쿼리 페이지네이션 자동 삽입은
     * FORCE INDEX가 낀 SQL에서 안전하게 동작하는지 보장되지 않아 직접 적는다.
     */
    @Query(value = """
            SELECT * FROM campaign FORCE INDEX (idx_campaign_owner_cursor)
            WHERE owner_id = :ownerId
              AND (:cursorId IS NULL OR campaign_id < :cursorId)
            ORDER BY campaign_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Campaign> findPageByOwnerId(
            @Param("ownerId") Long ownerId,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit);

    /*
     * 관리자의 전체 캠페인 목록 조회(#603) 커서 페이징이다. 점주 목록과 같은 이유로 campaignId 하나를
     * 커서로 쓴다. 가게와 상태 필터는 둘 다 선택이라 null이면 조건을 건너뛴다. 전체를 최신순으로
     * 훑는 조회라 기본키 역순 스캔을 쓰고 FORCE INDEX는 걸지 않는다. limit은 Pageable로 받는다.
     */
    @Query("""
            select c from Campaign c
            where (:storeId is null or c.storeId = :storeId)
              and (:status is null or c.status = :status)
              and (:cursorId is null or c.id < :cursorId)
            order by c.id desc
            """)
    List<Campaign> findAdminPage(
            @Param("storeId") Long storeId,
            @Param("status") CampaignStatus status,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
