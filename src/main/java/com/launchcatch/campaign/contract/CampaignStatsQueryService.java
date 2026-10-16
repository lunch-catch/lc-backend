package com.launchcatch.campaign.contract;

import java.time.LocalDate;

/**
 * 분석의 일별 집계 배치만 부르는 집계 조회다(설계 문서 2.2, 규칙 4). 캠페인이 집계 SQL을
 * 소유하고 묶은 개수만 돌려준다.
 */
public interface CampaignStatsQueryService {

    /** 그 영업일 23:59:59.999999(KST) 기준 ACTIVE 상태였던 캠페인 수. */
    long activeCampaignCount(LocalDate businessDate);
}
