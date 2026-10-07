package com.launchcatch.campaign.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemplateCreateRequest(
        @Schema(
                description = "템플릿 이름입니다. 100자 이하입니다.",
                example = "가을 신메뉴",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank @Size(max = 100) String name,

        @Schema(
                description = "LLM 에 전달할 관리자의 요청 문장입니다.",
                example = "가을 신메뉴 느낌으로 만들어줘",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank String requestPrompt
) {
}
