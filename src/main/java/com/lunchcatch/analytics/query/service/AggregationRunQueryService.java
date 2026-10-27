package com.lunchcatch.analytics.query.service;

import static com.lunchcatch.analytics.DailyAggregation.JOB_NAME;

import com.lunchcatch.ops.contract.BatchExecutionQueryService;
import com.lunchcatch.ops.contract.BatchExecutionRecord;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

/*
 * A-06 집계 실행 상태 조회다. 운영의 실행 기록에서 일별 집계 작업의 행만 골라 내준다.
 *
 * 작업 이름은 analytics의 공통 정의를 참조해 실행과 조회가 함께 사용한다.
 * 이 조회는 배치 실행기를 참조하지 않고, 실행 기록은 운영 소유라 운영의 조회 기능만 거친다.
 */
@Service
public class AggregationRunQueryService {

    private final BatchExecutionQueryService executionQuery;

    public AggregationRunQueryService(BatchExecutionQueryService executionQuery) {
        this.executionQuery = executionQuery;
    }

    public Optional<BatchExecutionRecord> findRun(LocalDate businessDate) {
        return executionQuery.findByBusinessDate(businessDate).stream()
                .filter(record -> JOB_NAME.equals(record.jobName()))
                .findFirst();
    }
}
