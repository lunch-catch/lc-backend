package com.launchcatch.campaign.contract;

import java.time.LocalDate;
import java.util.List;

/** 캠페인 정보를 공개한다. 조회 결과의 날짜와 필요한 컬럼은 호출하는 도메인에서 판단한다. */
public interface CampaignQueryService {

    /** storeId IN 조건으로 조회한다. 날짜 조건은 호출하는 도메인에서 판단한다. */
    List<CampaignInfo> findByStoreIdIn(List<Long> storeIds);

    /** 캠페인 PK 목록으로 조회한다. Repository.findAllById 결과를 CampaignInfo로 변환한다. */
    List<CampaignInfo> findAllById(List<Long> campaignIds);

    /**
     * 00:00 적재 대상을 조회한다. 그 영업일이 집행 기간에 들어가는 ACTIVE, 예약이 있는
     * PAUSED(OWNER, NO_POINTS) 캠페인을 돌려준다(ad_candidate.campaign_state 열 주석의 조건).
     */
    List<CampaignInfo> findServableOn(LocalDate businessDate);
}
