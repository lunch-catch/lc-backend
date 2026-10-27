package com.lunchcatch.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record StoreImagesCompleteRequest(
        @Schema(description = "진행 상태 조회로 받은 현재 버전입니다.", example = "3")
        @NotNull @PositiveOrZero Long version
) {
}
