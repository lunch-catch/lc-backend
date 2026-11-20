package com.lunchcatch.analytics.batch.service;

import com.lunchcatch.analytics.DailyAggregation;
import com.lunchcatch.analytics.exception.AnalyticsErrorCode;
import com.lunchcatch.analytics.exception.AnalyticsException;
import com.lunchcatch.ops.contract.AuditLogWriter;
import com.lunchcatch.ops.contract.BatchExecutionQueryService;
import com.lunchcatch.ops.contract.BatchExecutionRecord;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * A-05 수동 재계산이다. 요청은 그 날짜의 실행 행을 요청 표식으로 넘기고 감사 로그를 남긴 뒤 202 로 끝난다.
 *
 * 집계는 이 서버가 하지 않는다. 배치 서버의 이어받기가 소유자가 요청 표식인 행을 다음 스캔에 집어
 * DailyAggregationJob.run 으로 그 날짜만 집계한다(#1238). 관리자 배치 재실행(#1231)과 같은 길이다.
 *
 * 앱 서버에서 집계하면 요청용 시간 제한(쿼리와 트랜잭션 5초)을 받는다. 하루치 노출과 원장을 읽는 집계는
 * 5초를 넘어 재계산으로 복구되지 않는다. 배치 서버는 배치 설정의 시간 제한과 생존 신호를 그대로 받는다.
 *
 * 요청 표식이라 실행 행이 같아 정기 실행과 같은 날짜를 겹쳐 돌지 않는다. 실행 중이면 409 다. 이미 FINAL 인
 * 날짜도 다시 돌릴 수 있고, 공개 트랜잭션이 그날 행을 한 번에 교체해서 도는 동안에도 조회 결과가 비지 않는다.
 */
@Slf4j
@Service
public class AggregationRecalculationService {

    private static final String AUDIT_ACTION = "ANALYTICS_RECALCULATE";

    private final DailyAggregationRunner runner;
    private final BatchExecutionQueryService executionQuery;
    private final AuditLogWriter auditLogWriter;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AggregationRecalculationService(
            DailyAggregationRunner runner,
            BatchExecutionQueryService executionQuery,
            AuditLogWriter auditLogWriter,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.runner = runner;
        this.executionQuery = executionQuery;
        this.auditLogWriter = auditLogWriter;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /*
     * 날짜를 배치 서버에 넘기고 실행 행 ID 를 돌려준다.
     *
     * 오늘과 미래, 보존 기간 밖 날짜는 422, 이미 실행 중이면 409 다.
     *
     * 요청 표식과 감사 로그를 한 트랜잭션에 둔다. 감사 로그를 남기지 못하면 요청도 롤백된다. 배치 서버는
     * 커밋된 행만 보므로 감사 없는 재계산이 돌지 않는다.
     *
     * 접수 로그는 요청의 trace_id 아래에 영업일과 실행 행 ID 를 남긴다. 배치 서버 쪽 로그
     * (BATCH_MANUAL_RETRY_STARTED, ANALYTICS_DAILY_AGGREGATION_RESUMED)와 영업일로 이어 본다.
     */
    public long request(Long adminId, LocalDate businessDate) {
        LocalDate today = LocalDate.now(clock);
        if (businessDate == null
                || !businessDate.isBefore(today)
                || businessDate.isBefore(runner.oldestRetained(today))) {
            throw new AnalyticsException(AnalyticsErrorCode.UNAGGREGATABLE_DATE);
        }
        List<BatchExecutionRecord> records = executionQuery.findByBusinessDate(businessDate);
        transactions.executeWithoutResult(status -> {
            if (!runner.request(businessDate, records)) {
                throw new AnalyticsException(AnalyticsErrorCode.AGGREGATION_ALREADY_RUNNING);
            }
            auditLogWriter.write(adminId, AUDIT_ACTION, businessDate.toString(), DailyAggregation.JOB_NAME);
        });
        long runId = executionQuery.findByBusinessDate(businessDate).stream()
                .filter(record -> DailyAggregation.JOB_NAME.equals(record.jobName()))
                .findFirst()
                .orElseThrow()
                .runId();
        log.info("event=ANALYTICS_RECALCULATION_REQUESTED businessDate={} runId={}", businessDate, runId);
        return runId;
    }
}
