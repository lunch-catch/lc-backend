package com.lunchcatch.owner.dto;

import com.lunchcatch.store.contract.StoreInfoSaveResult;

public record StoreInfoResponse(Long storeId, String resumeStep, long version, String verificationStatus) {
    public static StoreInfoResponse from(StoreInfoSaveResult result) {
        return new StoreInfoResponse(result.storeId(), result.resumeStep(), result.version(), result.verificationStatus());
    }
}
