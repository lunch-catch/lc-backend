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
        String html,

        @Schema(description = "이 버전을 만들 때 LLM이 보낸 설명입니다. 없으면 빈 문자열입니다.",
                example = "따뜻한 주황 톤의 카드형 템플릿으로 만들었어요.")
        String message
) {

    public static TemplateVersionItemResponse from(TemplateVersion version) {
        return new TemplateVersionItemResponse(
                version.getVersionNumber(),
                version.getRequestPrompt(),
                version.getCreatedAt(),
                version.getHtmlContent(),
                version.getMessage() == null ? "" : version.getMessage()
        );
    }
}
