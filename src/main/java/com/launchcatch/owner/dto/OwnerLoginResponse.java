package com.launchcatch.owner.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.launchcatch.auth.Role;
import com.launchcatch.owner.entity.OwnerStatus;

public record OwnerLoginResponse(
        String email, Role role, OwnerStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean tutorialViewed
) {
}