package com.lunchcatch.store.dto;

import com.lunchcatch.store.entity.StoreRegistrationStep;

public record StoreRegistrationProgressSnapshot(Long storeId, StoreRegistrationStep resumeStep, long version) { }