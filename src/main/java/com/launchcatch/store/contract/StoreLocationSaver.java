package com.launchcatch.store.contract;

/*
 * 프론트에서 확정한 위치와 온보딩 진행 상태를 함께 저장하며 카카오 API는 호출하지 않는다.
 * 호출자는 같은 쓰기 트랜잭션에서 점주 행을 잠그고 ONBOARDING 상태를 확인해야 한다.
 * 저장이 실패하면 위치와 진행 상태, 버전을 모두 변경하지 않는다.
 */
public interface StoreLocationSaver {
    StoreLocationSaveResult save(Long ownerId, StoreLocationSaveCommand command);
}
