package com.launchcatch.store.contract;

/*
 * 사업자번호를 Mock 검증하고, 통과한 가게 기본 정보와 온보딩 진행 상태를 함께 저장한다.
 * 호출자는 같은 쓰기 트랜잭션에서 점주 행을 잠그고 ONBOARDING 상태를 확인해야 한다.
 * Mock 실패는 FAILED로 반환하며 기존 저장값과 버전은 유지한다.
 */
public interface StoreInfoSaver {
    StoreInfoSaveResult save(Long ownerId, StoreInfoSaveCommand command);
}
