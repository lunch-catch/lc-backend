package com.launchcatch.analytics.batch.job;

import static org.mockito.Mockito.verify;

import com.launchcatch.analytics.batch.service.DailyAggregationRunner;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;

@ExtendWith(MockitoExtension.class)
class DailyAggregationJobTest {

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
}
