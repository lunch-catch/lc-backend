package com.lunchcatch.store.contract;

/*
 * storeId는 가게 ID, resumeStep은 재개 단계, version은 다음 요청에 사용할 버전이다.
 * verificationStatus는 VERIFIED 또는 FAILED이며, 최초 검증 실패 시 storeId는 null이다.
 */
public record StoreInfoSaveResult(Long storeId, String resumeStep, long version, String verificationStatus) { }
