package com.launchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.exception.AnalyticsException;
import com.launchcatch.analytics.query.AggregationStatus;
import com.launchcatch.analytics.query.AnalyticsPeriod;
import com.launchcatch.analytics.query.TimeSeriesState;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimeSeriesStatusServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    @Mock
    private BatchExecutionQueryService batchExecutions;

    private TimeSeriesStatusService service;

    @BeforeEach
    void setUp() {
        service = new TimeSeriesStatusService(batchExecutions);
    }

    @Test
    void 구간의_모든_날짜가_성공했으면_FINAL이다() {
        LocalDateTime mondayFinishedAt = LocalDateTime.of(2026, 10, 6, 1, 5);
        LocalDateTime tuesdayFinishedAt = LocalDateTime.of(2026, 10, 7, 1, 7);
        when(batchExecutions.findByBusinessDate(MONDAY)).thenReturn(
                List.of(success(MONDAY, mondayFinishedAt)));
        when(batchExecutions.findByBusinessDate(MONDAY.plusDays(1))).thenReturn(
                List.of(success(MONDAY.plusDays(1), tuesdayFinishedAt)));

        TimeSeriesState state = service.resolve(
                AnalyticsPeriod.WEEKLY, MONDAY, MONDAY.plusDays(1));

        assertThat(state.aggregatedAt()).isEqualTo(tuesdayFinishedAt);
        assertThat(state.items()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(AggregationStatus.FINAL));
    }

    @Test
    void 성공하지_않은_날짜가_하나라도_있으면_IN_PROGRESS다() {
        LocalDateTime finishedAt = LocalDateTime.of(2026, 10, 6, 1, 5);
        when(batchExecutions.findByBusinessDate(MONDAY)).thenReturn(List.of(success(MONDAY, finishedAt)));
        when(batchExecutions.findByBusinessDate(MONDAY.plusDays(1))).thenReturn(List.of(running(MONDAY.plusDays(1))));

        TimeSeriesState state = service.resolve(
                AnalyticsPeriod.WEEKLY, MONDAY, MONDAY.plusDays(1));

        assertThat(state.aggregatedAt()).isEqualTo(finishedAt);
        assertThat(state.items()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(AggregationStatus.IN_PROGRESS));
    }

    @Test
    void 성공한_실행이_없으면_집계_시각은_null이다() {
        when(batchExecutions.findByBusinessDate(MONDAY)).thenReturn(List.of());

        TimeSeriesState state = service.resolve(AnalyticsPeriod.DAILY, MONDAY, MONDAY);

        assertThat(state.aggregatedAt()).isNull();
        assertThat(state.items()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(AggregationStatus.IN_PROGRESS));
    }

    @Test
    void 상한을_초과한_기간은_실행_이력을_조회하기_전에_거절한다() {
        assertThatThrownBy(() -> service.resolve(
                AnalyticsPeriod.DAILY, MONDAY, MONDAY.plusDays(92)))
                .isInstanceOf(AnalyticsException.class);

        verifyNoInteractions(batchExecutions);
    }

    private BatchExecutionRecord success(LocalDate date, LocalDateTime finishedAt) {
        return new BatchExecutionRecord(
                1L, "DAILY_AGGREGATION", date, "SUCCESS", finishedAt.minusMinutes(5),
                finishedAt, 1, "batch-1", 0, null);
    }

    private BatchExecutionRecord running(LocalDate date) {
        return new BatchExecutionRecord(
                2L, "DAILY_AGGREGATION", date, "RUNNING", date.atStartOfDay(),
                null, null, "batch-1", 0, null);
    }
}
