package com.lunchcatch.campaign.contract;

import java.time.LocalDate;

/*
 * store_audience 의 유일 키(storeId, targetRadius, gender, ageGroup) 한 칸과
 * 그 값(memberCount)이다. 광고 서빙이 00:00 (10)단계에서 집계해 덮어쓴다.
 * businessDate는 그 집계 기준 영업일이다. 00:00에 도는 작업이라 집계 시점과 저장 시점의
 * 날짜가 갈릴 수 있어, 부분 갱신된 표에서 오늘 값인지 구별하는 근거로 같이 넘긴다(#178).
 */
public record StoreAudienceCell(
        long storeId,
        int targetRadius,
        TargetGender gender,
        AgeGroup ageGroup,
        int memberCount,
        LocalDate businessDate) {
}
