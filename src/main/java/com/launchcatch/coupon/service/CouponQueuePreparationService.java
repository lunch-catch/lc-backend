package com.launchcatch.coupon.service;

import static com.launchcatch.coupon.CouponBatchTask.BATCH_SIZE;

import com.launchcatch.coupon.CouponBatchTask;
import com.launchcatch.coupon.repository.CouponBatchRepository;
import com.launchcatch.ops.contract.BatchAttemptGuard;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class CouponQueuePreparationService {

    private final CouponBatchRepository batches;
    private final CouponSequenceRecoveryService recovery;
    private final BatchAttemptGuard guard;
    private final TransactionTemplate transactions;

    public CouponQueuePreparationService(CouponBatchRepository batches, CouponSequenceRecoveryService recovery,
            BatchAttemptGuard guard, PlatformTransactionManager manager) {
        this.batches = batches;
        this.recovery = recovery;
        this.guard = guard;
        this.transactions = new TransactionTemplate(manager);
    }

    /*
     * 회차 ID를 1,000건씩 읽고 회차마다 별도 트랜잭션에서 발급 오픈 전에 Redis 큐를 만든다.
     * 회차 하나의 큐 생성이 실패하면 그 회차는 첫 발급 요청의 복구에 맡기고 다음 회차를 처리한다.
     * 실행 세대를 잃으면 남은 회차를 처리하지 않고 실패를 전달한다.
     */
    public int prepare(LocalDate date, int attemptNo) {
        long cursor = 0;
        int count = 0;
        List<Long> ids;
        do {
            ids = batches.eventIds(date, cursor);
            for (long id : ids) {
                count += Objects.requireNonNull(transactions.execute(status -> {
                    guard.assertCurrentAttempt(CouponBatchTask.QUEUE_PREPARE.jobName(), date, attemptNo);
                    try {
                        return recovery.prepare(id) ? 1 : 0;
                    } catch (RuntimeException error) {
                        status.setRollbackOnly();
                        CouponFailureDetails details = CouponFailureDetails.from(error);
                        log.warn("event=COUPON_QUEUE_PREPARE_FAILED businessDate={} couponEventId={} exceptionType={} reason={} sqlState={} sqlErrorCode={} origin=\"{}\"",
                                date, id, details.exceptionType(), CouponFailureDetails.reason(error),
                                details.sqlState(), details.sqlErrorCode(), details.origin());
                        return 0;
                    }
                }));
                cursor = id;
            }
        } while (ids.size() == BATCH_SIZE);
        return count;
    }
}
