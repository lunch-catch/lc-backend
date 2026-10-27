package com.lunchcatch.store.contract;

import java.util.List;

/*
 * version은 ONBOARDING 저장 시 현재 버전이며 ACTIVE 저장 시 null로 보낸다.
 * businessHours에는 영업일만 보내도 되며 빠진 요일은 휴무·모든 시각 null로 저장한다.
 * 빈 목록은 일주일 전체 휴무를 뜻하며 목록 자체의 null과 중복 요일은 허용하지 않는다.
 * 영업일의 시작·종료 시각은 필수이며 브레이크타임은 시작·종료를 함께 보내거나 모두 null로 보낸다.
 * 라스트오더는 없으면 null로 보내며 생략한 요일과 선택 시각의 기존 값은 유지하지 않는다.
 */
public record StoreHoursSaveCommand(Long version, List<OwnerStoreSnapshot.BusinessHour> businessHours) {
    public StoreHoursSaveCommand {
        businessHours = businessHours == null ? null : List.copyOf(businessHours);
    }
}
