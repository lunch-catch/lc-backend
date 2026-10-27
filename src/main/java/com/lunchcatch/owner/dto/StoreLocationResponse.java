package com.lunchcatch.owner.dto;

import com.lunchcatch.store.contract.StoreLocationSaveResult;

public record StoreLocationResponse(Long storeId, String resumeStep, long version) {
    public static StoreLocationResponse from(StoreLocationSaveResult result) {
        return new StoreLocationResponse(result.storeId(), result.resumeStep(), result.version());
    }
}
