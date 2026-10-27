package com.lunchcatch.campaign.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemplateRenameRequest(
        @Schema(
                description = "새 템플릿 이름입니다. 앞뒤 공백을 자른 뒤 100자 이하입니다.",
                example = "가을 신메뉴",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank @Size(max = 100) String name
) {

    /*
     * 앞뒤 공백을 먼저 자르고 검증한다(#880). Template 엔티티가 저장할 때 같은 규칙으로 자르므로, 공백을 포함한 길이로
     * 검증하면 자른 값은 100자 이하인데도 400 이 된다.
     */
    public TemplateRenameRequest {
        name = name == null ? null : name.strip();
    }
}
