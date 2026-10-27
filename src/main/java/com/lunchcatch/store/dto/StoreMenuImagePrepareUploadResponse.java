package com.lunchcatch.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record StoreMenuImagePrepareUploadResponse(
        @Schema(description = "이 업로드 요청의 식별자입니다. 확인 요청에 그대로 보냅니다.")
        String uploadId,

        @Schema(description = "이 주소로 파일을 직접 PUT 업로드합니다. 10분 안에 사용해야 합니다.")
        String uploadUrl
) {
    public static StoreMenuImagePrepareUploadResponse from(StoreMenuImagePrepareResult result) {
        return new StoreMenuImagePrepareUploadResponse(result.uploadId(), result.uploadUrl());
    }
}
