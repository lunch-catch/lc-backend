package com.launchcatch.store.dto;

import com.launchcatch.store.entity.StoreRegistrationStep;

public record StoreRegistrationProgressSnapshot(Long storeId, StoreRegistrationStep resumeStep, long version) { }