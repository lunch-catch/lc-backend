package com.launchcatch.store.contract;

import java.util.List;

/** 가게 조회 계약의 누락 데이터와 좌표 정책은 docs/design/store/store-query-contract.md를 따른다. */
public interface StoreQueryService {
    /** 현재 가게를 일괄 조회한다. 존재하지 않는 가게 ID는 제외하며 좌표는 null일 수 있다. */
    List<StoreInfo> findAllById(List<Long> storeIds);

    /** 현재 소유 관계만 확인한다. 가게 미존재와 다른 점주 소유는 모두 false다. */
    boolean canManageStore(Long ownerId, Long storeId);

    /** 현재 메뉴를 일괄 조회한다. 존재하지 않는 메뉴 ID는 제외하며 가격은 원 단위 할인 전 가격이다. */
    List<StoreMenuInfo> findMenusByIdIn(List<Long> menuIds);
}
