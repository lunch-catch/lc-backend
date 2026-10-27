package com.lunchcatch.ops.service;

import com.lunchcatch.ops.contract.BatchAttemptGuard;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BatchAttemptGuardImpl implements BatchAttemptGuard {

    private final BatchExecutionService batchExecutionService;

    @Override
    public void assertCurrentAttempt(String jobName, LocalDate businessDate, int attemptNo) {
        batchExecutionService.assertCurrentAttempt(jobName, businessDate, attemptNo);
    }
}
