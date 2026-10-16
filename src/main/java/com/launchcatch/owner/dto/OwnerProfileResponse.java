package com.launchcatch.owner.dto;

import com.launchcatch.owner.entity.OwnerStatus;

public record OwnerProfileResponse(String email, OwnerStatus status, boolean tutorialViewed, String registrationStep) { }