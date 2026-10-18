package com.launchcatch.store.dto;

import com.launchcatch.store.StoreOnboardingReadiness;

public record StoreOnboardingQueryRow(Long storeId, long version, boolean finalized,
                                      StoreOnboardingReadiness readiness) { }