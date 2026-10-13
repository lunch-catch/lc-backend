package com.launchcatch.billing.contract;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/*
 * 광고 서빙이 후보 정렬과 노출 상한 계산에 쓰는 예산 소진 진행률 조회다(I-06).
 *
 * 당일 소진 카운터와 시간대별 목표만 읽고 원장 합계는 읽지 않으므로 서빙 핫패스에서 불러도 된다.
 * 광고 서빙은 자기 카운터를 따로 만들지 않는다.
 */
public interface BudgetQueryService {

    /*
     * 한 번에 넘길 수 있는 캠페인 수의 상한이다. 정산은 넘겨받은 후보를 한 번에 메모리에 올려 조회하므로
     * 광고 서빙은 후보가 이보다 많으면 이 값 이하로 나눠 부른다.
     */
    int MAX_CAMPAIGN_IDS = 5000;

    /*
     * 캠페인 여러 개의 그날 진행률을 한 번에 돌려준다. 캠페인마다 나눠 부르지 않는다.
     *
     * campaignIds가 MAX_CAMPAIGN_IDS를 넘으면 IllegalArgumentException으로 거부한다. 잘라서 처리하지 않는다.
     * at은 ClockConfig.ZONE(Asia/Seoul) 기준 시각이다. businessDate도 같은 기준의 영업일이다.
     *
     * 그날 예산 계획이 없는 캠페인은 결과에서 빠진다. 예약이 없어 후보가 아니다.
     * 카운터 장애면 대체값 없이 예외를 던진다. 금전 값이라 틀린 값보다 실패가 낫고,
     * 그 회차의 후보 처리는 광고 서빙이 정한다.
     */
    List<BudgetProgress> getProgress(Collection<Long> campaignIds, LocalDate businessDate, LocalDateTime at);
}
