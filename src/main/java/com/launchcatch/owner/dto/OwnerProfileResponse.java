package com.launchcatch.owner.dto;

import com.launchcatch.owner.entity.OwnerStatus;

public record OwnerProfileResponse(OwnerStatus status, boolean tutorialViewed) { }