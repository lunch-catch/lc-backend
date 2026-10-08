package com.launchcatch.owner.dto;

import com.launchcatch.auth.Role;
import com.launchcatch.owner.entity.OwnerStatus;

public record OwnerSignupResponse(String email, Role role, OwnerStatus status) {
}