package com.launchcatch.campaign.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record TemplateActiveUpdateRequest(
        @Schema(
                description = "점주에게 노출할지 여부입니다.",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull Boolean active
) {
}
