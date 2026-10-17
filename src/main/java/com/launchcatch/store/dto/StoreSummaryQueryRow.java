package com.launchcatch.store.dto;

public record StoreSummaryQueryRow(long ownerId, long storeId, String name, boolean finalized,
                                   String businessRegistrationNumber) { }