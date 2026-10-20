package com.launchcatch.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record StoreImagePrepareUploadResponse(
        @Schema(description = "이 업로드 요청의 식별자입니다. 확인 요청에 그대로 보냅니다.")
        String uploadId,

        @Schema(description = "이 주소로 파일을 직접 PUT 업로드합니다. 10분 안에 사용해야 합니다.")
        String uploadUrl,

        @Schema(description = "이 이미지가 인테리어 이미지 중 몇 번째로 표시되는지입니다. 로고면 null입니다.", example = "1")
        Integer sortOrder
) {
    public static StoreImagePrepareUploadResponse from(StoreImagePrepareResult result) {
        return new StoreImagePrepareUploadResponse(result.uploadId(), result.uploadUrl(), result.sortOrder());
    }
}
