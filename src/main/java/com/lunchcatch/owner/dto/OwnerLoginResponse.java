package com.lunchcatch.owner.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lunchcatch.auth.Role;
import com.lunchcatch.owner.entity.OwnerStatus;

public record OwnerLoginResponse(
        String email, Role role, OwnerStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean tutorialViewed
) {
}