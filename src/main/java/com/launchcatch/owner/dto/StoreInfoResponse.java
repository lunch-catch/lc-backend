package com.launchcatch.owner.dto;

import com.launchcatch.store.contract.StoreInfoSaveResult;

public record StoreInfoResponse(Long storeId, String resumeStep, long version, String verificationStatus) {
    public static StoreInfoResponse from(StoreInfoSaveResult result) {
        return new StoreInfoResponse(result.storeId(), result.resumeStep(), result.version(), result.verificationStatus());
    }
}
