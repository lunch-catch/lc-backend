package com.lunchcatch.ops.contract;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 배치 실행 기록 한 행이다. status는 RUNNING, SUCCESS, FAILED 중 하나다. */
public record BatchExecutionRecord(
        Long runId,
        String jobName,
        LocalDate businessDate,
        String status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Integer processedCount,
        String executedBy,
        int retryCount,
        String failureReason,
        int attemptNo
) {
}
