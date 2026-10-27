package com.lunchcatch.adserving.contract;

import java.time.LocalDate;
import java.util.List;

/*
 * 분석의 일별 집계 배치만 부르는 집계 조회다(설계 문서 2.2, 규칙 4). 광고 서빙이 집계 SQL 을 소유하고 묶은 건수만 돌려준다.
 *
 * 영업일은 노출을 받은 날이다. 정산이 차감하는 영업일과 같다(PointDeductor 계약). 자정을 넘겨 받은 노출은 다음 영업일로 센다.
 * 그날 노출은 다음 날 00:00 전까지 들어오므로 그 뒤에 부른다. 끝나지 않은 영업일을 넘겨도 막지 않고 그 시점까지 저장된 값을 돌려준다.
 */
public interface AdservingStatsQueryService {

    // 영업일의 캠페인별 노출과 찜 건수다. 캠페인 ID 오름차순이고 그날 노출도 찜도 없는 캠페인은 결과에 없다
    List<CampaignImpressionStats> campaignStats(LocalDate businessDate);

    // 영업일의 캠페인과 회원별 무효 노출 건수다. 무효 노출이 있는 조합만 캠페인 ID, 회원 ID 오름차순으로 전부 돌려준다
    List<MemberInvalidImpressionCount> memberInvalidCounts(LocalDate businessDate);

    /*
     * 영업일의 캠페인별 유효 노출 회원 수와 유효 노출이 많은 상위 topMembers 명의 노출 합이다. 캠페인 ID 오름차순이다.
     * topMembers 가 1 미만이면 IllegalArgumentException 을 던진다.
     */
    List<CampaignMemberConcentration> memberConcentrations(LocalDate businessDate, int topMembers);
}
