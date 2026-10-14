package com.launchcatch.store.entity;

public enum StoreRegistrationStep {
    TERMS, STORE, BUSINESS_VERIFICATION, LOCATION, HOURS, IMAGES, MENU;

    public StoreRegistrationStep afterSave() {
        return switch (this) {
            case TERMS -> STORE;
            case STORE -> BUSINESS_VERIFICATION;
            case BUSINESS_VERIFICATION -> LOCATION;
            case LOCATION -> HOURS;
            case HOURS -> IMAGES;
            case IMAGES -> MENU;
            // 마지막 입력 단계는 유지하고 등록 완료 여부는 store.finalized로 판단한다.
            case MENU -> MENU;
        };
    }
}