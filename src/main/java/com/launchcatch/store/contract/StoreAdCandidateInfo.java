package com.launchcatch.store.contract;

import java.math.BigDecimal;

/** 광고 후보 스냅샷용 가게 정보이며 검증된 LOGO가 없으면 storeImageUrl은 null이다. */
public record StoreAdCandidateInfo(Long storeId, String storeName, String categoryCode,
        String storeImageUrl, BigDecimal latitude, BigDecimal longitude) { }