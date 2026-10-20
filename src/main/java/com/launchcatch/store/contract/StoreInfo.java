package com.launchcatch.store.contract;

/** 최종 등록된 가게의 대표 이미지 URL과 좌표(위도, 경도)를 반환하며 검증된 필수 대표 이미지가 없으면 데이터 정합성 오류로 처리한다. */
public record StoreInfo(Long storeId, String storeName, String storeImageUrl,
        Double latitude, Double longitude) { }