package com.launchcatch.campaign.template.dto;

import com.launchcatch.campaign.template.entity.TemplateStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record TemplateCreateResponse(
        @Schema(description = "템플릿 ID입니다.", example = "1")
        Long templateId,

        @Schema(description = "템플릿 이름입니다.", example = "가을 신메뉴")
        String name,

        @Schema(description = "저장된 임시저장 버전 번호입니다.", example = "1")
        int versionNumber,

        @Schema(description = "템플릿 상태입니다. 생성 직후는 항상 DRAFT입니다.", example = "DRAFT")
        TemplateStatus status,

        @Schema(description = "검증을 거쳐 저장된 HTML입니다.")
        String html,

        @Schema(description = "정화 과정에서 제거된 태그와 속성입니다. 없으면 빈 배열입니다.", example = "[\"script\", \"div[onclick]\"]")
        List<String> removedElements
) {
}
