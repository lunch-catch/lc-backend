package com.launchcatch.analytics.batch.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record RecalculationRequest(
        @Schema(description = "다시 집계할 영업일입니다. 어제 이전이고 보존 기간(13개월) 안이어야 합니다.",
                example = "2026-09-30")
        LocalDate businessDate
) {
}
