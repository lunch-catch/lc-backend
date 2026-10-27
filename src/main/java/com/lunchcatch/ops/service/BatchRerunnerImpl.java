package com.lunchcatch.ops.service;

import com.lunchcatch.ops.contract.BatchRerunner;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BatchRerunnerImpl implements BatchRerunner {

    private final BatchExecutionService batchExecutionService;

    @Override
    public boolean rerun(String jobName, LocalDate businessDate) {
        return batchExecutionService.rerun(jobName, businessDate);
    }
}
