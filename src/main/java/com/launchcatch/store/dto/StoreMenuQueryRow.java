package com.launchcatch.store.dto;

// 메뉴의 소속 가게와 할인 전 가격을 조회한 결과로, imageObjectKey는 이미지 주소(URL)가 아니라, 저장소에서 이미지 파일을 찾는 데 쓰는 값이다.
public record StoreMenuQueryRow(Long menuId, Long storeId, String imageObjectKey, String name, Long price) { }