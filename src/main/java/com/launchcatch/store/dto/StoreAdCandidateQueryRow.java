package com.launchcatch.store.dto;

import java.math.BigDecimal;

// 광고 후보용 조회 결과의 이미지 저장 키는 서비스가 공개 URL로 변환한다.
public record StoreAdCandidateQueryRow(Long storeId, String name, String categoryCode,
        String imageObjectKey, BigDecimal latitude, BigDecimal longitude) { }