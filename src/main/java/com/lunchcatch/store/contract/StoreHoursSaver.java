package com.lunchcatch.store.contract;

/*
 * 본인 가게의 영업시간을 일주일 전체로 교체한다.
 * 호출자는 같은 쓰기 트랜잭션에서 점주 행을 먼저 잠그고 현재 상태에 맞는 메서드를 호출해야 한다.
 */
public interface StoreHoursSaver {
    // ONBOARDING 초안에 저장하며 최초 저장은 IMAGES로 진행하고 재저장은 재개 위치를 유지하면서 버전을 1 증가시킨다.
    StoreHoursSaveResult saveOnboarding(Long ownerId, StoreHoursSaveCommand command);

    // ACTIVE의 최종 등록된 가게만 수정하며 온보딩 진행 상태와 버전은 읽거나 변경하지 않는다.
    StoreHoursSaveResult saveActive(Long ownerId, StoreHoursSaveCommand command);
}
