package com.launchcatch.store.contract;

/** price는 원 단위 할인 전 가격이며 필수 이미지가 검증된 정상 메뉴만 이 타입으로 반환한다. */
public record StoreMenuInfo(Long menuId, Long storeId, String menuImageUrl, String menuName, Long price) { }