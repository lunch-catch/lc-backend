package com.launchcatch.campaign.poster.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PosterCreateResponse(
        @Schema(description = "포스터 ID입니다.", example = "1")
        Long posterId,

        @Schema(description = "이 포스터가 속한 캠페인 ID입니다.", example = "10")
        Long campaignId,

        @Schema(description = "포스터에 쓴 템플릿 ID입니다.", example = "1")
        Long templateId,

        @Schema(description = "목록 검색과 정렬용 제목입니다. \"{가게명} - {템플릿 이름}\" 형태입니다.")
        String title,

        @Schema(description = "5개 슬롯이 전부 채워진 완성 HTML입니다.")
        String html
) {
}
