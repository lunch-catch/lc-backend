package com.launchcatch.campaign.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemplateRenameRequest(
        @Schema(
                description = "새 템플릿 이름입니다. 100자 이하입니다.",
                example = "가을 신메뉴",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank @Size(max = 100) String name
) {
}
