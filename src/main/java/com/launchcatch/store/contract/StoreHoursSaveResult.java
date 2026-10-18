package com.launchcatch.store.contract;

/*
 * storeId는 저장한 가게 ID다.
 * ONBOARDING은 저장 후 재개 단계와 증가한 버전을 반환하며 다음 저장에는 이 버전을 사용한다.
 * ACTIVE 저장은 resumeStep과 version이 모두 null이다.
 */
public record StoreHoursSaveResult(Long storeId, String resumeStep, Long version) { }
