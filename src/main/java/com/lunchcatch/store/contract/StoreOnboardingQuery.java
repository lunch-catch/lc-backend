package com.lunchcatch.store.contract;

/*
 * 점주의 단계별 완료 여부, 지금 진행할 단계와 최종 등록 가능 여부를 조회한다.
 * 호출자는 인증된 점주의 상태가 ONBOARDING 또는 ACTIVE인지 먼저 확인해야 한다.
 * 조회 중에는 가게나 진행 상태를 생성하거나 변경하지 않는다.
 */
public interface StoreOnboardingQuery {
    StoreOnboardingStatus getProgress(Long ownerId);
}