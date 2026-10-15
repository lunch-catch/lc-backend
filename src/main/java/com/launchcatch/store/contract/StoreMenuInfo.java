package com.launchcatch.store.contract;

/** price는 원 단위 할인 전 가격이며, 이미지가 없으면 menuImageUrl은 null이다. */
public record StoreMenuInfo(Long menuId, Long storeId, String menuImageUrl, String menuName, Long price) { }