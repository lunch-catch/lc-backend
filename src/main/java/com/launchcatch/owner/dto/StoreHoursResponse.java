package com.launchcatch.owner.dto;

import com.launchcatch.store.contract.StoreHoursSaveResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record StoreHoursResponse(Long storeId,
        @Schema(description = "ACTIVE 저장은 null") String resumeStep,
        @Schema(description = "ACTIVE 저장은 null") Long version) {
    public static StoreHoursResponse from(StoreHoursSaveResult result) {
        return new StoreHoursResponse(result.storeId(), result.resumeStep(), result.version());
    }
}
