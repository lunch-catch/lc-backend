package com.launchcatch.store.contract;

/** 점주 목록용 가게 요약이며 사업자등록번호의 가운데 두 자리는 마스킹한다. */
public record StoreSummary(long ownerId, long storeId, String name, boolean finalized,
                           String businessRegistrationNumber) { }