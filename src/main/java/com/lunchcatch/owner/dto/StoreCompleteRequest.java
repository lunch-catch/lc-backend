package com.lunchcatch.owner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record StoreCompleteRequest(
        @Schema(description = "최신 조회 응답의 온보딩 버전", example = "5")
        @NotNull @PositiveOrZero Long version) { }
