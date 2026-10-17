package com.launchcatch.store.contract;

import java.math.BigDecimal;

/** 광고 후보 스냅샷용 가게 정보이며 두 좌표가 모두 있는 최종 등록 가게만 반환한다. */
public record StoreAdCandidateInfo(Long storeId, String storeName, String categoryCode,
        String storeImageUrl, BigDecimal latitude, BigDecimal longitude) { }