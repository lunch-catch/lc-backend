package com.launchcatch.analytics.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.launchcatch.analytics.batch.AggregationOutcome;
import com.launchcatch.analytics.batch.service.DailyAggregationRunner;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;

@ExtendWith(MockitoExtension.class)
class DailyAggregationJobTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 16);

    @Mock
    private DailyAggregationRunner runner;

    @InjectMocks
    private DailyAggregationJob job;

    @Test
    void 정해진_시각에_실행기를_한_번_부른다() {
        Mockito.when(runner.runAll()).thenReturn(Map.of());

        job.run();

        verify(runner).runAll();
    }

    @Test
    void 이어받기와_수동_재실행이_찾는_작업_이름은_일별_집계다() {
        assertThat(job.jobName()).isEqualTo(DailyAggregationRunner.JOB_NAME);
    }

    @Test
    void 이어받으면_넘겨받은_날짜와_세대로_그_날짜만_집계하고_다시_잡지_않는다() {
        Mockito.when(runner.aggregate(DATE, 4)).thenReturn(AggregationOutcome.SUCCEEDED);

        job.run(DATE, 4);

        verify(runner).aggregate(DATE, 4);
        verify(runner, never()).runAll();
        verify(runner, never()).acquire(any(), any());
    }

    @Test
    void 이어받은_실행이_실행권을_잃어도_예외를_던지지_않는다() {
        Mockito.when(runner.aggregate(DATE, 4)).thenReturn(AggregationOutcome.LEASE_LOST);

        assertThatCode(() -> job.run(DATE, 4)).doesNotThrowAnyException();
    }
}
