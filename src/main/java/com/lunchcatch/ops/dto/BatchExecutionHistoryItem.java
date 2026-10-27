package com.lunchcatch.ops.dto;

import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.ops.entity.BatchExecutionLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

// 배치 실행 이력 조회(#575)의 항목 하나다. 필드 이름은 BatchExecutionRecord 와 A-06 을 따른다.
public record BatchExecutionHistoryItem(
        @Schema(description = "실행 기록 ID입니다. 재실행 API 응답의 runId와 같습니다.", example = "301")
        Long runId,
        @Schema(description = "작업명입니다.", example = "DAILY_0000")
        String jobName,
        @Schema(description = "처리 대상 영업일입니다.", example = "2026-09-30")
        LocalDate businessDate,
        @Schema(description = "RUNNING, SUCCESS, FAILED 중 하나입니다.", example = "FAILED")
        String status,
        @Schema(description = "이 시도를 시작한 시각입니다(KST). 재실행하면 그 시각으로 바뀝니다.")
        OffsetDateTime startedAt,
        @Schema(description = "끝난 시각입니다(KST). 실행 중이면 null입니다.")
        OffsetDateTime finishedAt,
        @Schema(description = "처리 건수입니다. 실행 중이거나 실패했거나 건수를 남기지 않는 작업이면 null입니다.")
        Integer processedCount,
        @Schema(description = "지금 그 기록을 가진 배치 서버입니다.", example = "batch-02")
        String executedBy,
        @Schema(description = "이 시도에서 일시적 오류로 자동 재실행한 횟수입니다(0~2).", example = "2")
        int retryCount,
        @Schema(description = "실패 사유 원문입니다. FAILED일 때만 값이 있습니다.")
        String failureReason,
        @Schema(description = "실행 세대입니다. 처음 1이고 이어받기와 수동 재실행마다 1씩 오릅니다.", example = "1")
        int attemptNo,
        @Schema(description = "지금 배치 수동 재실행을 부를 수 있으면 true입니다.", example = "true")
        boolean retryable
) {

    public static BatchExecutionHistoryItem of(BatchExecutionLog log, boolean retryable) {
        return new BatchExecutionHistoryItem(log.getId(), log.getJobName(), log.getBusinessDate(),
                log.getStatus().name(), toOffset(log.getCreatedAt()), toOffset(log.getFinishedAt()),
                log.getProcessedCount(), log.getOwnerId(), log.getRetryCount(), log.getFailureReason(),
                log.getAttemptNo(), retryable);
    }

    private static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
