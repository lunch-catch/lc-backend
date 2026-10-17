package com.launchcatch.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StoreMenuImagePrepareUploadRequest(
        @Schema(description = "이미지를 올릴 메뉴의 식별자입니다.", example = "10")
        @NotNull Long menuId,

        @Schema(description = "원본 파일명입니다.", example = "menu.jpg")
        @NotBlank String originalFilename,

        @Schema(description = "파일 MIME 타입입니다. image/jpeg, image/png만 허용합니다.", example = "image/jpeg")
        @NotBlank String contentType,

        @Schema(description = "파일 크기(byte)입니다. 10MB(10485760)를 넘을 수 없습니다.", example = "204800")
        @NotNull Long fileSize
) {
}
