package com.lunchcatch.analytics.query.controller.dto;

import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.ops.contract.BatchExecutionRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

public record AggregationRunResponse(
        @Schema(description = "실행 기록 ID입니다.", example = "301")
        Long runId,
        @Schema(description = "작업 이름입니다. 항상 DAILY_AGGREGATION입니다.", example = "DAILY_AGGREGATION")
        String jobName,
        @Schema(description = "집계한 영업일입니다.", example = "2026-09-30")
        LocalDate businessDate,
        @Schema(description = "RUNNING, SUCCESS, FAILED 중 하나입니다.", example = "SUCCESS")
        String status,
        @Schema(description = "실행 시작 시각입니다(KST).")
        OffsetDateTime startedAt,
        @Schema(description = "실행 종료 시각입니다(KST). 실행 중이면 null입니다.")
        OffsetDateTime finishedAt,
        @Schema(description = "공개 트랜잭션에서 쓴 집계 행 수입니다. 아직 기록되지 않았으면 null입니다.", example = "5")
        Integer processedCount,
        @Schema(description = "실행한 서버입니다.", example = "batch-02")
        String executedBy,
        @Schema(description = "자동 재실행 횟수입니다.", example = "0")
        int retryCount,
        @Schema(description = "실패 사유입니다. 실패가 아니면 null입니다.")
        String failureReason
) {

    public static AggregationRunResponse from(BatchExecutionRecord record) {
        return new AggregationRunResponse(record.runId(), record.jobName(), record.businessDate(),
                record.status(), toOffset(record.startedAt()), toOffset(record.finishedAt()),
                record.processedCount(), record.executedBy(), record.retryCount(), record.failureReason());
    }

    private static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
