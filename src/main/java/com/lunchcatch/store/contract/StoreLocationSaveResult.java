package com.lunchcatch.store.contract;

/*
 * storeId는 저장한 가게 ID, resumeStep은 재개 단계, version은 다음 저장 요청에 사용할 버전이다.
 * 최초 저장은 HOURS로 진행하고 재저장은 기존 재개 단계를 유지하며, 성공마다 버전이 1 증가한다.
 */
public record StoreLocationSaveResult(Long storeId, String resumeStep, long version) { }
