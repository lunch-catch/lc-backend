package com.launchcatch.store.contract;

/** 등록된 메뉴의 검증된 필수 이미지가 누락된 데이터 오류다. */
public final class StoreMenuDataException extends RuntimeException {
    private final long menuId;

    public StoreMenuDataException(long menuId) {
        super("최종 등록된 메뉴의 검증된 이미지가 누락되었습니다. menuId=" + menuId);
        this.menuId = menuId;
    }

    public long menuId() {
        return menuId;
    }
}
