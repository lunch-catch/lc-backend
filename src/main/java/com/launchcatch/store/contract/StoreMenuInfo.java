package com.launchcatch.store.contract;

/** price는 원 단위 할인 전 가격이며 최종 등록된 가게의 필수 메뉴 이미지 누락은 오류로 처리한다. */
public record StoreMenuInfo(Long menuId, Long storeId, String menuImageUrl, String menuName, Long price) { }