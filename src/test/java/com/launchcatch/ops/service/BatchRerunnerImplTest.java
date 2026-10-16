package com.launchcatch.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BatchRerunnerImplTest {
    private final BatchExecutionService batchExecutionService = mock(BatchExecutionService.class);
    private final BatchRerunnerImpl rerunner = new BatchRerunnerImpl(batchExecutionService);

    @Test
    void 받은_인자와_반환값을_그대로_위임한다() {
        LocalDate date = LocalDate.of(2026, 10, 15);
        when(batchExecutionService.rerun("DAILY_AGGREGATION", date)).thenReturn(true);

        assertThat(rerunner.rerun("DAILY_AGGREGATION", date)).isTrue();
    }
}
