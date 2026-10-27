package com.lunchcatch.campaign.contract;

import java.time.LocalDate;

/**
 * 분석의 일별 집계 배치만 부르는 집계 조회다(설계 문서 2.2, 규칙 4). 캠페인이 집계 SQL을
 * 소유하고 묶은 개수만 돌려준다.
 */
public interface CampaignStatsQueryService {

    /**
     * 그 영업일 23:59:59.999999(KST) 기준 ACTIVE 상태였던 캠페인 수.
     *
     * @throws IllegalArgumentException businessDate가 오늘이거나 미래면 던진다. 그날이 끝나기
     *         전에는 아직 남은 전이가 로그에 없어 "그날의 최종 값"을 보장할 수 없다
     */
    long activeCampaignCount(LocalDate businessDate);
}
