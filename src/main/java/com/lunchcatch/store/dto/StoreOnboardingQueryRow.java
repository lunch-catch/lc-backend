package com.lunchcatch.store.dto;

import com.lunchcatch.store.StoreOnboardingReadiness;

public record StoreOnboardingQueryRow(Long storeId, long version, boolean finalized,
                                      StoreOnboardingReadiness readiness) { }