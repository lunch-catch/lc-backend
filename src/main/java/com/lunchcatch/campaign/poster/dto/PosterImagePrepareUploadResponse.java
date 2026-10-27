package com.lunchcatch.campaign.poster.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PosterImagePrepareUploadResponse(
        @Schema(description = "업로드할 객체 키입니다. 포스터 생성 요청에 그대로 보냅니다.")
        String objectKey,

        @Schema(description = "이 주소로 파일을 직접 PUT 업로드합니다. 10분 안에 사용해야 합니다.")
        String uploadUrl
) {
}
