package com.launchcatch.adserving.contract;

import java.time.LocalDate;
import java.util.List;

/*
 * 분석의 일별 집계 배치만 부르는 집계 조회다(설계 문서 2.2, 규칙 4). 광고 서빙이 집계 SQL 을 소유하고 묶은 건수만 돌려준다.
 *
 * 영업일은 노출을 받은 날이다. 서빙 시간대가 10:00~12:59 이고 노출 인정 시간이 30분이라 카드를 내려준 영업일과 갈리지 않는다.
 * 그날 노출은 13:30 까지 들어오므로 그 뒤에 부른다. 끝나지 않은 영업일을 넘겨도 막지 않고 그 시점까지 저장된 값을 돌려준다.
 */
public interface AdservingStatsQueryService {

    // 영업일의 캠페인별 노출과 찜 건수다. 캠페인 ID 오름차순이고 그날 노출도 찜도 없는 캠페인은 결과에 없다
    List<CampaignImpressionStats> campaignStats(LocalDate businessDate);

    // 영업일의 캠페인과 회원별 무효 노출 건수다. 무효 노출이 있는 조합만 캠페인 ID, 회원 ID 오름차순으로 전부 돌려준다
    List<MemberInvalidImpressionCount> memberInvalidCounts(LocalDate businessDate);

    // 영업일의 캠페인별 유효 노출 회원 수와 유효 노출이 많은 상위 topMembers 명의 노출 합이다. 캠페인 ID 오름차순이다
    List<CampaignMemberConcentration> memberConcentrations(LocalDate businessDate, int topMembers);
}
