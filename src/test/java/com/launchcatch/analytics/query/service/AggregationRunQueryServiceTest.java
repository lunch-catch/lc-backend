package com.launchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AggregationRunQueryServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);

    @Mock
    private BatchExecutionQueryService executionQuery;

    @InjectMocks
    private AggregationRunQueryService service;

    private static BatchExecutionRecord record(long runId, String jobName) {
        return new BatchExecutionRecord(runId, jobName, DATE, "SUCCESS",
                LocalDateTime.of(2026, 10, 1, 1, 0), LocalDateTime.of(2026, 10, 1, 1, 5), 5, "batch-02", 0, null);
    }

    @Test
    void 그날의_실행_기록_중_일별_집계_행만_돌려준다() {
        when(executionQuery.findByBusinessDate(DATE)).thenReturn(List.of(
                record(1L, "BILLING_DAILY_RELEASE"), record(301L, "DAILY_AGGREGATION")));

        Optional<BatchExecutionRecord> run = service.findRun(DATE);

        assertThat(run).hasValueSatisfying(found -> assertThat(found.runId()).isEqualTo(301L));
    }

    @Test
    void 일별_집계가_돈_적이_없으면_비어_있다() {
        when(executionQuery.findByBusinessDate(DATE)).thenReturn(List.of(record(1L, "BILLING_DAILY_RELEASE")));

        assertThat(service.findRun(DATE)).isEmpty();
    }
}
