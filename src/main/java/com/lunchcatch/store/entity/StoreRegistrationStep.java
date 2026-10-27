package com.lunchcatch.store.entity;

public enum StoreRegistrationStep {
    TERMS, STORE_INFO, LOCATION, HOURS, IMAGES, MENU;

    public StoreRegistrationStep afterSave() {
        return switch (this) {
            case TERMS -> STORE_INFO;
            case STORE_INFO -> LOCATION;
            case LOCATION -> HOURS;
            case HOURS -> IMAGES;
            case IMAGES -> MENU;
            // 마지막 입력 단계는 유지하고 등록 완료 여부는 store.finalized로 판단한다.
            case MENU -> MENU;
        };
    }
}