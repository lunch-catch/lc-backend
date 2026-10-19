package com.launchcatch.ops.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record BatchExecutionRetryResponse(
        @Schema(description = "실행 기록 ID입니다.", example = "301")
        long runId,
        @Schema(description = "재실행한 작업명입니다.", example = "DAILY_0000")
        String jobName,
        @Schema(description = "재실행한 영업일입니다.", example = "2026-09-30")
        LocalDate businessDate,
        @Schema(description = "접수 시점의 상태입니다. 항상 RUNNING입니다.", example = "RUNNING")
        String status
) {

    private static final String RUNNING = "RUNNING";

    public static BatchExecutionRetryResponse accepted(long runId, String jobName, LocalDate businessDate) {
        return new BatchExecutionRetryResponse(runId, jobName, businessDate, RUNNING);
    }
}
