package com.lunchcatch.store.dto;

import com.lunchcatch.store.entity.StoreImageType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StoreImagePrepareUploadRequest(
        @Schema(description = "올릴 이미지 종류입니다. LOGO는 가게당 1개이고 다시 올리면 교체합니다. "
                + "INTERIOR는 최대 3개입니다. 비우면 INTERIOR로 봅니다.", example = "LOGO")
        StoreImageType imageType,

        @Schema(description = "원본 파일명입니다.", example = "interior.jpg")
        @NotBlank String originalFilename,

        @Schema(description = "파일 MIME 타입입니다. image/jpeg, image/png만 허용합니다.", example = "image/jpeg")
        @NotBlank String contentType,

        @Schema(description = "파일 크기(byte)입니다. 10MB(10485760)를 넘을 수 없습니다.", example = "204800")
        @NotNull Long fileSize
) {
}
