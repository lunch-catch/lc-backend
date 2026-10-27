package com.lunchcatch.store.contract;

/** 점주 목록용 가게 요약이며 사업자등록번호는 123-45-***** 형식으로 마스킹한다. */
public record StoreSummary(long ownerId, long storeId, String name, boolean finalized,
                           String businessRegistrationNumber) { }
