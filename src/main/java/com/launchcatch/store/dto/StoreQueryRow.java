package com.launchcatch.store.dto;

import java.math.BigDecimal;

// DB 조회 결과를 담으며, imageObjectKey는 서비스에서 공개 URL로 바꾸기 전의 저장 키다.
public record StoreQueryRow(Long storeId, String name, String imageObjectKey,
        BigDecimal latitude, BigDecimal longitude) { }