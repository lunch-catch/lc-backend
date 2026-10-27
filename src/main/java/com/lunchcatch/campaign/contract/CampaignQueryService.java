package com.lunchcatch.campaign.contract;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 캠페인 정보를 공개한다. 조회 결과의 날짜와 필요한 컬럼은 호출하는 도메인에서 판단한다. */
public interface CampaignQueryService {

    /** storeId IN 조건으로 조회한다. 날짜 조건은 호출하는 도메인에서 판단한다. */
    List<CampaignInfo> findByStoreIdIn(List<Long> storeIds);

    /** 캠페인 PK 목록으로 조회한다. Repository.findAllById 결과를 CampaignInfo로 변환한다. */
    List<CampaignInfo> findAllById(List<Long> campaignIds);

    /**
     * 00:00 적재 대상을 조회한다. 그 영업일이 집행 기간에 들어가는 ACTIVE, 예약이 있는
     * PAUSED(OWNER, NO_POINTS) 캠페인을 돌려준다(ad_candidate.campaign_state 열 주석의 조건).
     * 정산의 00:00 예약 대상 조회도 조건이 같아 이 메서드를 그대로 쓴다(#198, 따로 두면 조건이
     * 두 곳에 흩어져 한쪽만 바뀌면 어긋난다).
     * 결과가 10000행을 넘으면 예외로 거부한다(qa-performance-efficiency-guideline.md, 한 번에
     * 메모리에 올리는 행 수 상한, #178).
     */
    List<CampaignInfo> findServableOn(LocalDate businessDate);

    /** 서울 기준 오늘이 집행 기간에 들고 상태가 SCHEDULED 또는 ACTIVE인 캠페인을 조회한다. */
    List<CampaignInfo> findEligibleCampaigns();

    /**
     * 그 점주의 상태 목록에 속한 캠페인을 조회한다. 집행 기간 판단은 호출하는 도메인이
     * startDate, endDate로 한다.
     */
    List<CampaignInfo> findByOwnerIdAndStatusIn(Long ownerId, List<CampaignStatus> statuses);

    /**
     * 그 점주에게 집행 중인 캠페인이 있는지 본다. "집행 중"은 DRAFT, ENDED가 아닌 상태
     * 전부다(ACTIVE, SCHEDULED, PAUSED는 이유를 가리지 않는다). exists 수준으로 가볍다.
     */
    boolean hasLiveCampaign(Long ownerId);

    /**
     * 점주 ID 목록 중 집행 중인 캠페인이 하나라도 있는 점주 ID만 돌려준다. hasLiveCampaign과
     * 같은 조건을 쓴다. 입력이 비면 빈 Set이다.
     */
    Set<Long> findOwnerIdsWithLiveCampaign(Collection<Long> ownerIds);

    /**
     * 그 캠페인 행을 잠그고(SELECT ... FOR UPDATE) 최신 상태로 조회한다(#378). 호출자가 이
     * 결과를 읽은 뒤 그 캠페인에 쓰기를 하는 동안 다른 트랜잭션이 같은 캠페인을 바꾸지 못하게
     * 막는다. 호출자의 쓰기 트랜잭션 안에서 불러야 잠금이 그 트랜잭션이 끝날 때까지 유지된다.
     * 트랜잭션 없이 부르면 구현이 거부한다(MANDATORY, #876). 없는 캠페인 ID면 빈 값이다.
     */
    Optional<CampaignInfo> findByIdForUpdate(Long campaignId);
}
