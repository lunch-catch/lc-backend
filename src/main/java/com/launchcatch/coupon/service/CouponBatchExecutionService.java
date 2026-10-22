package com.launchcatch.coupon.service;

import com.launchcatch.coupon.CouponBatchTask;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponBatchExecutionService {

    private static final int MAX_RETRIES = 2;

    private final BatchExecutionService executions;
    private final BatchExecutionQueryService executionQuery;
    private final RetryBackoff backoff;

    /*
     * 스케줄러가 점유한 실행의 세대는 기존 공개 조회에서 읽으며 공통 ops 코드는 변경하지 않는다.
     * 업무 처리는 재시도 경로를 거친 뒤 같은 실행 세대로 성공이나 실패를 기록한다.
     * 이 메서드를 트랜잭션으로 감싸면 재시도 대기 중에도 잠금이 유지되므로 각 업무 서비스가 커밋을 맡는다.
     */
    public void runClaimed(CouponBatchTask task, LocalDate date, IntUnaryOperator operation) {
        int attemptNo = 0;
        try {
            attemptNo = executionQuery.findByBusinessDate(date).stream()
                    .filter(row -> task.jobName().equals(row.jobName())).findFirst().orElseThrow().attemptNo();
            int currentAttempt = attemptNo;
            int processed = retry(task, date, attemptNo, () -> operation.applyAsInt(currentAttempt));
            executions.succeedAttempt(task.jobName(), date, attemptNo, processed);
        } catch (RuntimeException failure) {
            try {
                // 세대를 읽지 못했으면 현재 실행을 안전하게 닫을 수 없으므로 오류 로그만 남긴다.
                if (attemptNo > 0) {
                    executions.failAttempt(task.jobName(), date, attemptNo, describe(failure));
                }
            } catch (RuntimeException recordFailure) {
                failure.addSuppressed(recordFailure);
                CouponFailureDetails details = CouponFailureDetails.from(recordFailure);
                log.error("event=COUPON_BATCH_FAILURE_RECORD_FAILED job={} businessDate={} attemptNo={} exceptionType={} httpStatus={} sqlState={} sqlErrorCode={} origin=\"{}\"",
                        task.jobName(), date, attemptNo, details.exceptionType(), details.httpStatus(),
                        details.sqlState(), details.sqlErrorCode(), details.origin());
            }
            CouponFailureDetails details = CouponFailureDetails.from(failure);
            log.error("event=COUPON_BATCH_FAILED job={} businessDate={} attemptNo={} exceptionType={} httpStatus={} sqlState={} sqlErrorCode={} origin=\"{}\"",
                    task.jobName(), date, attemptNo, details.exceptionType(), details.httpStatus(),
                    details.sqlState(), details.sqlErrorCode(), details.origin(), details.stackTrace());
        }
    }

    /*
     * 최초 실행과 StandaloneJob의 복구 실행 모두 쿠폰의 모든 실행 오류를 추가 두 번까지 재시도한다.
     * 로컬 상한과 기존 DB의 retry_count를 함께 검사해 서버가 바뀌어도 재시도 횟수를 초과하지 않는다.
     * 복구를 호출한 공통 실행기도 같은 DB 상한을 사용하므로 쿠폰의 재시도가 소진되면 더 실행하지 않는다.
     * 대기 중 실행권을 잃거나 스레드가 중단되면 마지막 오류를 전달하고 추가 처리를 멈춘다.
     */
    public int retry(CouponBatchTask task, LocalDate date, int attemptNo, IntSupplier operation) {
        for (int retryNo = 0; ; retryNo++) {
            try {
                return operation.getAsInt();
            } catch (RuntimeException failure) {
                if (retryNo == MAX_RETRIES
                        || !executions.recordRetryAttempt(task.jobName(), date, attemptNo, describe(failure))
                        || !backoff.pause(retryNo + 1)
                        || !executions.renewAttempt(task.jobName(), date, attemptNo)) {
                    throw failure;
                }
            }
        }
    }

    private String describe(RuntimeException failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
