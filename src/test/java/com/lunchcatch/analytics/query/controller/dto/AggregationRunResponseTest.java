package com.lunchcatch.analytics.query.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.ops.contract.BatchExecutionRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AggregationRunResponseTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);

    @Test
    void 시작과_종료_시각에_KST_오프셋을_붙인다() {
        BatchExecutionRecord record = new BatchExecutionRecord(301L, "DAILY_AGGREGATION", DATE, "SUCCESS",
                LocalDateTime.of(2026, 10, 1, 1, 0, 0), LocalDateTime.of(2026, 10, 1, 1, 5, 0),
                5, "batch-02", 0, null, 1);

        AggregationRunResponse response = AggregationRunResponse.from(record);

        assertThat(response.startedAt()).hasToString("2026-10-01T01:00+09:00");
        assertThat(response.finishedAt()).hasToString("2026-10-01T01:05+09:00");
    }

    @Test
    void 실행_중이면_종료_시각은_null이다() {
        BatchExecutionRecord record = new BatchExecutionRecord(301L, "DAILY_AGGREGATION", DATE, "RUNNING",
                LocalDateTime.of(2026, 10, 1, 1, 0, 0), null, null, "batch-02", 0, null, 1);

        AggregationRunResponse response = AggregationRunResponse.from(record);

        assertThat(response.startedAt()).hasToString("2026-10-01T01:00+09:00");
        assertThat(response.finishedAt()).isNull();
    }
}
