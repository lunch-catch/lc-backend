package com.launchcatch.campaign.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/*
 * store_audience.gender 컬럼의 값이다. campaign.contract.TargetGender(ALL 포함)와는 이름이
 * 같지만, DB 마이그레이션은 바꾸지 않기로 했다(#218). CHECK 제약이 여전히
 * ('MALE','FEMALE','OTHER')만 허용해서, ALL은 Java 쪽 이름일 뿐 DB에는 기존 OTHER 자리에
 * 그대로 저장된다. 이 분리는 StoreAudienceGenderConverter가 담당한다.
 */
@Getter
@RequiredArgsConstructor
public enum StoreAudienceGender {
    MALE("MALE"),
    FEMALE("FEMALE"),
    ALL("OTHER");

    private final String dbValue;
}
