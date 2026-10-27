package com.lunchcatch.analytics.batch.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record RecalculationResponse(
        @Schema(description = "실행 기록 ID입니다. A-06 응답의 runId와 같습니다.", example = "301")
        long runId,
        @Schema(description = "집계를 시작한 영업일입니다.", example = "2026-09-30")
        LocalDate businessDate,
        @Schema(description = "접수 시점의 상태입니다. 항상 RUNNING입니다.", example = "RUNNING")
        String status
) {

    private static final String RUNNING = "RUNNING";

    public static RecalculationResponse running(long runId, LocalDate businessDate) {
        return new RecalculationResponse(runId, businessDate, RUNNING);
    }
}
