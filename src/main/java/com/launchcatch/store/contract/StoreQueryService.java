package com.launchcatch.store.contract;

import java.util.List;

/** 가게 조회 계약의 누락 데이터와 좌표 정책은 docs/design/store/store-query-contract.md를 따른다. */
public interface StoreQueryService {
    /** 최종 등록된 가게를 일괄 조회한다. 존재하지 않거나 최종 등록 전인 가게 ID는 제외한다. */
    List<StoreInfo> findAllById(List<Long> storeIds);

    /** 본인 소유이면서 최종 등록된 가게인지 확인한다. 가게 미존재, 다른 점주 소유와 등록 중인 가게는 false다. */
    boolean canManageFinalizedStore(Long ownerId, Long storeId);

    /** 최종 등록된 가게의 메뉴를 일괄 조회한다. 존재하지 않거나 최종 등록 전 가게에 속한 메뉴 ID는 제외하며 가격은 원 단위 할인 전 가격이다. */
    List<StoreMenuInfo> findMenusByIdIn(List<Long> menuIds);
}
