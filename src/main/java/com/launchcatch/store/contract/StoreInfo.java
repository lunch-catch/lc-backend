package com.launchcatch.store.contract;

import java.math.BigDecimal;

/** 이미지가 없으면 URL은 null이며, 위치 저장 전에는 좌표(위도, 경도) 각각이 null일 수 있다. */
public record StoreInfo(Long storeId, String storeName, String storeImageUrl,
        BigDecimal latitude, BigDecimal longitude) { }