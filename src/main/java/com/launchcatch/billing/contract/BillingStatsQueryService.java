package com.launchcatch.billing.contract;

import java.time.LocalDate;
import java.util.List;

/*
 * 분석의 일별 집계 배치만 부르는 집계 조회다(설계 문서 2.2, 규칙 4). 정산이 집계 SQL 을 소유하고 묶은 합계와 건수만 돌려준다.
 *
 * 영업일은 ClockConfig.ZONE(Asia/Seoul) 기준이다. 소진액(DEDUCT - 양수 ADJUST)은 분석 배치가 계산하고 이 조회는 합계만 내준다.
 * 끝나지 않은 영업일을 넘겨도 막지 않는다. 그 시점까지 기록된 값을 돌려준다.
 */
public interface BillingStatsQueryService {

    // 영업일의 점주와 캠페인별 원장 유형별 합계다. 그날 원장이 없는 조합은 결과에 없다
    List<OwnerCampaignLedgerSum> ledgerSums(LocalDate businessDate);

    // 영업일의 캠페인별 과금 상태 수다. 그날 판정이 없는 캠페인은 결과에 없다
    List<CampaignBillingCount> billingCounts(LocalDate businessDate);

    // 영업일의 캠페인별 하루 예산과 단가다. 그날 예산 계획이 없는 캠페인은 결과에 없다
    List<CampaignDailyBudget> dailyBudgets(LocalDate businessDate);

    /*
     * 기록 영업일에 기록된 정정(양수 ADJUST)이 가리키는 원래 영업일 목록이다. 날짜 오름차순이고 중복이 없다.
     * 분석이 이미 집계한 영업일 중 다시 집계할 날을 찾는 데 쓴다. 정정 대상이 없는 ADJUST(캠페인 없는 조정)는 목록에 없다.
     * 기록 영업일과 같은 날을 가리키는 정정도 목록에 들어간다.
     */
    List<LocalDate> correctionTargetDates(LocalDate recordedDate);

    // 영업일의 미소진 잔액과 원장 대조용 합계, 원장 불일치 건수다
    DailyLedgerTotals dailyTotals(LocalDate businessDate);
}
