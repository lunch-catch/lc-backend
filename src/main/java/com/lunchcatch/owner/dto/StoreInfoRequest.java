package com.lunchcatch.owner.dto;

import com.lunchcatch.store.contract.StoreInfoSaveCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record StoreInfoRequest(@NotNull @PositiveOrZero Long version, String name, String categoryCode,
                               String representativeName, String businessRegistrationNumber, String phone) {
    public StoreInfoSaveCommand toCommand() {
        return new StoreInfoSaveCommand(version, name, categoryCode, representativeName, businessRegistrationNumber, phone);
    }
}
