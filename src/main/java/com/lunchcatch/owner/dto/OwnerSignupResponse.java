package com.lunchcatch.owner.dto;

import com.lunchcatch.auth.Role;
import com.lunchcatch.owner.entity.OwnerStatus;

public record OwnerSignupResponse(String email, Role role, OwnerStatus status) {
}