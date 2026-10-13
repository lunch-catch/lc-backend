package com.launchcatch.campaign.contract;

/*
 * store_audience 의 유일 키(storeId, targetRadius, gender, ageGroup) 한 칸과
 * 그 값(memberCount)이다. 광고 서빙이 00:00 (10)단계에서 집계해 덮어쓴다.
 */
public record StoreAudienceCell(
        long storeId,
        int targetRadius,
        TargetGender gender,
        AgeGroup ageGroup,
        int memberCount) {
}
