package com.launchcatch.campaign.template.dto;

import com.launchcatch.campaign.template.entity.Template;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record TemplateRenameResponse(
        @Schema(description = "템플릿 ID입니다.", example = "1")
        Long templateId,

        @Schema(description = "바뀐 템플릿 이름입니다.", example = "가을 신메뉴")
        String name,

        @Schema(description = "마지막으로 수정한 관리자 ID입니다.", example = "1")
        Long lastModifiedBy,

        @Schema(description = "마지막으로 수정한 관리자 이름입니다. 관리자를 찾을 수 없으면 null입니다.", example = "홍길동")
        String lastModifiedByName,

        @Schema(description = "마지막으로 수정한 시각입니다.")
        LocalDateTime lastModifiedAt
) {

    public static TemplateRenameResponse of(Template template, String lastModifiedByName) {
        return new TemplateRenameResponse(
                template.getId(), template.getName(), template.getLastModifiedBy(), lastModifiedByName,
                template.getLastModifiedAt());
    }
}
