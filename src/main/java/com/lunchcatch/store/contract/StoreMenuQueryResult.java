package com.lunchcatch.store.contract;

import java.util.List;

/** 정상 메뉴와 필수 이미지의 데이터 오류가 난 메뉴 ID를 구분한 일괄 조회 결과다. */
public record StoreMenuQueryResult(List<StoreMenuInfo> menus, List<Long> invalidMenuIds) {
    public StoreMenuQueryResult {
        menus = List.copyOf(menus);
        invalidMenuIds = List.copyOf(invalidMenuIds);
    }
}
