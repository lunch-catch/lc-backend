package com.lunchcatch.owner.dto;

import com.lunchcatch.owner.entity.OwnerStatus;

public record OwnerProfileResponse(String email, OwnerStatus status, boolean tutorialViewed, String registrationStep) { }