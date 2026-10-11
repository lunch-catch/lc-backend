package com.launchcatch.owner.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OwnerTermsAgreementRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Agreement> agreements) {
    public record Agreement(@NotBlank @Size(max = 50) String termsType,
                            @NotBlank @Size(max = 30) String termsVersion,
                            @NotNull Boolean agreed) { }
}