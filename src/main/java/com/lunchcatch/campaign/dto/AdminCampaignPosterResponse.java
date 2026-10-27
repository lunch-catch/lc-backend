package com.lunchcatch.campaign.dto;

import com.lunchcatch.campaign.poster.entity.Poster;
import io.swagger.v3.oas.annotations.media.Schema;

public record AdminCampaignPosterResponse(
        @Schema(description = "포스터 ID입니다.", example = "9")
        Long posterId,

        @Schema(description = "포스터를 만든 템플릿 ID입니다.", example = "3")
        Long templateId,

        @Schema(description = "포스터 제목입니다.", example = "점심 특선")
        String title,

        @Schema(description = "저장된 포스터 HTML입니다.")
        String html
) {
    public static AdminCampaignPosterResponse from(Poster poster) {
        return new AdminCampaignPosterResponse(
                poster.getId(), poster.getTemplateId(), poster.getTitle(), poster.getHtmlContent());
    }
}
