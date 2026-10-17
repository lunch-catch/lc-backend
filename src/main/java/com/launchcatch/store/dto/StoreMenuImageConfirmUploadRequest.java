package com.launchcatch.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record StoreMenuImageConfirmUploadRequest(
        @Schema(description = "prepareUpload 응답으로 받은 업로드 식별자입니다.")
        @NotBlank String uploadId
) {
}
