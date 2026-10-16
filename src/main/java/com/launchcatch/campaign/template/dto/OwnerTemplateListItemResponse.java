package com.launchcatch.campaign.template.dto;

import com.launchcatch.campaign.template.entity.Template;
import io.swagger.v3.oas.annotations.media.Schema;

public record OwnerTemplateListItemResponse(
        @Schema(description = "템플릿 ID입니다.", example = "1")
        Long templateId,

        @Schema(description = "템플릿 이름입니다.", example = "가을 신메뉴")
        String name,

        @Schema(description = "게시된 템플릿의 확정 HTML입니다.")
        String html
) {

    public static OwnerTemplateListItemResponse of(Template template) {
        return new OwnerTemplateListItemResponse(template.getId(), template.getName(), template.getHtmlContent());
    }
}
