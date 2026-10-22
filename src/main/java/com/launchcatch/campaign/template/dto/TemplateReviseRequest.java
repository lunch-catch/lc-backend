package com.launchcatch.campaign.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemplateReviseRequest(
        @Schema(
                description = "LLM 에 전달할 관리자의 수정 요청 문장입니다. 500자 이하입니다.",
                example = "버튼 색상을 바꿔줘",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank @Size(max = 500) String requestPrompt,

        @Schema(
                description = "클라이언트가 생성하는 요청 식별자입니다. 같은 값으로 다시 요청하면 새로 만들지 않고 처음 응답을 그대로 돌려줍니다.",
                example = "5b3f6c2a-9e1d-4b7a-8f0a-1c2d3e4f5a6b",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank @Size(max = 64) String requestId
) {
}
