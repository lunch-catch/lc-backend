package com.launchcatch.store.contract;

/** 최종 등록된 가게의 대표 이미지 URL과 좌표(위도, 경도)를 반환하며 #647의 임시 정책에 따라 검증된 대표 이미지가 없으면 URL은 null이다. */
public record StoreInfo(Long storeId, String storeName, String storeImageUrl,
        Double latitude, Double longitude) { }