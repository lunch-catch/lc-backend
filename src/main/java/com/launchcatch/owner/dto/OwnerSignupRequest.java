package com.launchcatch.owner.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OwnerSignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 10, max = 20) String password
) {
    @Override
    public String toString() { return "OwnerSignupRequest[REDACTED]"; }
}