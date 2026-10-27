package com.lunchcatch.ops.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BatchAttemptGuardImplTest {
    private final BatchExecutionService batchExecutionService = mock(BatchExecutionService.class);
    private final BatchAttemptGuardImpl guard = new BatchAttemptGuardImpl(batchExecutionService);

    @Test
    void 받은_인자를_그대로_위임한다() {
        LocalDate date = LocalDate.of(2026, 10, 15);

        guard.assertCurrentAttempt("DAILY_AGGREGATION", date, 2);

        verify(batchExecutionService).assertCurrentAttempt("DAILY_AGGREGATION", date, 2);
    }
}
