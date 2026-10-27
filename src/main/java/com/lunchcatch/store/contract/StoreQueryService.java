package com.lunchcatch.store.contract;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 가게 조회 계약의 누락 데이터와 좌표 정책은 docs/design/store/store-query-contract.md를 따른다. */
public interface StoreQueryService {
    /** 가게 생성 전에도 약관과 각 단계의 현재 저장·진행 상태를 함께 반환하며 저장하지 않는다. */
    OwnerStoreOverview getOwnerStoreOverview(Long ownerId);

    /** 인증된 점주 화면용 조회다. 초안도 포함하며 가게가 없으면 빈 결과를 반환한다. 저장하지 않는다. */
    Optional<OwnerStoreSnapshot> findOwnerStore(Long ownerId);

    /** 광고 후보용 조회 한 번에 전달할 수 있는 가게 ID 수이며 중복도 포함한다. */
    int MAX_AD_CANDIDATE_STORE_IDS = 5000;

    /**
     * 광고 후보용 가게를 일괄 조회하며 미존재·등록 전·좌표 누락 가게는 제외한다.
     * 검증된 LOGO가 없으면 이미지 URL은 null이다.
     * @throws IllegalArgumentException ID가 유효하지 않거나 중복 포함 5,000개를 초과한 경우
     */
    List<StoreAdCandidateInfo> findAdCandidatesByStoreIds(List<Long> storeIds);

    /** 최종 등록 전 가게도 포함해 점주 ID로 일괄 조회하며 ID가 100개를 초과하면 IllegalArgumentException을 던진다. */
    List<StoreSummary> findSummariesByOwnerIds(Collection<Long> ownerIds);

    /** 상호에 keyword 가 들어간 가게의 점주 ID를 반환하며 limit는 1~10000이고 검색 결과가 limit를 초과하면 IllegalArgumentException을 던진다. */
    List<Long> findOwnerIdsByNameContaining(String keyword, int limit);

    /** 최종 등록된 가게를 일괄 조회한다. 존재하지 않거나 최종 등록 전인 가게 ID는 제외한다. */
    List<StoreInfo> findAllById(List<Long> storeIds);

    /** 본인 소유이면서 최종 등록된 가게인지 확인한다. 가게 미존재, 다른 점주 소유와 등록 중인 가게는 false다. */
    boolean canManageStore(Long ownerId, Long storeId);

    /**
     * 정상 메뉴와 필수 이미지의 데이터 오류가 난 메뉴 ID를 반환한다.
     * 존재하지 않거나 최종 등록 전 가게에 속한 메뉴 ID는 두 목록에서 제외하며 가격은 원 단위 할인 전 가격이다.
     * CDN 설정 오류와 DB 장애는 데이터 오류 목록으로 바꾸지 않고 전파한다.
     */
    StoreMenuQueryResult findMenusByIdIn(List<Long> menuIds);
}
