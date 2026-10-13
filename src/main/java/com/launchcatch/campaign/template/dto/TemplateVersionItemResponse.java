package com.launchcatch.campaign.template.dto;

import com.launchcatch.campaign.template.entity.TemplateVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record TemplateVersionItemResponse(
        @Schema(description = "버전 번호입니다.", example = "2")
        int versionNumber,

        @Schema(description = "이 버전을 만들 때 관리자가 입력한 요청 문장입니다.", example = "버튼 색상을 바꿔줘")
        String requestPrompt,

        @Schema(description = "이 버전이 만들어진 시각입니다.")
        LocalDateTime createdAt,

        @Schema(description = "이 버전의 HTML입니다.")
        String html
) {

    public static TemplateVersionItemResponse from(TemplateVersion version) {
        return new TemplateVersionItemResponse(
                version.getVersionNumber(),
                version.getRequestPrompt(),
                version.getCreatedAt(),
                version.getHtmlContent()
        );
    }
}
