package com.launchcatch.ops.service;

import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.repository.BatchExecutionLogRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BatchExecutionQueryServiceImpl implements BatchExecutionQueryService {

    private final BatchExecutionLogRepository batchExecutionLogRepository;

    @Override
    public List<BatchExecutionRecord> findByBusinessDate(LocalDate businessDate) {
        return batchExecutionLogRepository.findByBusinessDate(businessDate).stream().map(this::toRecord).toList();
    }

    /** startedAt은 created_at을 쓴다. 점유 행이 RUNNING으로 생성되는 시점이 실행 시작이다. */
    private BatchExecutionRecord toRecord(BatchExecutionLog log) {
        return new BatchExecutionRecord(
                log.getId(), log.getJobName(), log.getBusinessDate(), log.getStatus().name(),
                log.getCreatedAt(), log.getFinishedAt(), log.getProcessedCount(),
                log.getOwnerId(), log.getRetryCount(), log.getFailureReason());
    }
}
