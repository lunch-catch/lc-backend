package com.launchcatch.analytics.query.service;

import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

/*
 * A-06 집계 실행 상태 조회다. 운영의 실행 기록에서 일별 집계 작업의 행만 골라 내준다.
 *
 * 작업 이름은 분석 배치 쪽 상수를 참조하지 않고 문자열로만 맞춘다. 조회 패키지는 배치 패키지를
 * 모르고, 실행 기록은 운영 소유라 운영의 조회 기능만 거친다.
 */
@Service
public class AggregationRunQueryService {

    private static final String JOB_NAME = "DAILY_AGGREGATION";

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
