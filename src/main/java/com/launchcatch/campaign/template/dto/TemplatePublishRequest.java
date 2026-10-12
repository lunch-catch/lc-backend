package com.launchcatch.campaign.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TemplatePublishRequest(
        @Schema(
                description = "게시할 임시저장 버전 번호입니다.",
                example = "3",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull @Positive Integer versionNumber
) {
}
