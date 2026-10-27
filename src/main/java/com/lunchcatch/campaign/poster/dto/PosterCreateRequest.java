package com.lunchcatch.campaign.poster.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PosterCreateRequest(
        @Schema(description = "포스터에 쓸 템플릿 ID입니다. 게시되고 활성인 템플릿만 가능합니다.", example = "1")
        @NotNull Long templateId,

        @Schema(description = "prepareUpload로 받아 실제 업로드까지 마친 객체 키입니다.")
        @NotBlank @Size(max = 1024) String objectKey
) {
}
