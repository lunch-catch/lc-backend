package com.lunchcatch.store;

import com.lunchcatch.store.entity.StoreRegistrationStep;

// 최종 등록에서도 같은 필수 조건과 선택 메뉴 검증 결과를 사용할 수 있다.
public record StoreOnboardingReadiness(boolean terms, boolean storeInfo, boolean location,
                                       boolean hours, boolean images, int menuCount, boolean validMenus) {
    public boolean completed(StoreRegistrationStep step) {
        return switch (step) {
            case TERMS -> terms;
            case STORE_INFO -> storeInfo;
            case LOCATION -> location;
            case HOURS -> hours;
            case IMAGES -> images;
            case MENU -> menuCount > 0 && validMenus;
        };
    }

    public StoreRegistrationStep firstIncompleteStep() {
        for (var step : StoreRegistrationStep.values()) {
            if (!completed(step)) {
                return step;
            }
        }
        return StoreRegistrationStep.MENU;
    }

    public boolean canFinalize() {
        return terms && storeInfo && location && hours && images && validMenus;
    }
}