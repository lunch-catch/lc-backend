package com.lunchcatch.coupon.service;

import com.lunchcatch.coupon.CouponBatchTask;
import com.lunchcatch.coupon.config.CouponProperties;
import com.lunchcatch.coupon.contract.CouponIssueOpenedEvent;
import com.lunchcatch.coupon.entity.CouponEvent;
import com.lunchcatch.coupon.entity.CouponEventStatus;
import com.lunchcatch.coupon.repository.CouponBatchRepository;
import com.lunchcatch.coupon.repository.CouponEventRepository;
import com.lunchcatch.ops.contract.BatchAttemptGuard;
import com.lunchcatch.global.logging.TransactionLogging;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class CouponLifecycleService {

    private final CouponBatchRepository batches;
    private final CouponEventRepository events;
    private final BatchAttemptGuard guard;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final CouponProperties properties;

    public CouponLifecycleService(CouponBatchRepository batches, CouponEventRepository events,
            BatchAttemptGuard guard, ApplicationEventPublisher publisher, Clock clock,
            PlatformTransactionManager manager,
            CouponProperties properties) {
        this.batches = batches;
        this.events = events;
        this.guard = guard;
        this.publisher = publisher;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.properties = properties;
    }

    /*
     * API의 발급 시간 검사가 실제 마감을 보장하므로 마감 배치는 회차의 캠페인 상태를 바꾸지 않는다.
     * 사용 중인 쿠폰에 영향을 주지 않고 종료 시각 도달과 대상 회차 수를 실행 이력으로 남긴다.
     */
    public int close(LocalDate date, int attemptNo) {
        return Objects.requireNonNull(transactions.execute(status -> {
            guard.assertCurrentAttempt(CouponBatchTask.CLOSE.jobName(), date, attemptNo);
            LocalDateTime now = LocalDateTime.now(clock);
            if (date.isAfter(now.toLocalDate())
                    || (date.equals(now.toLocalDate()) && now.toLocalTime().isBefore(properties.issueEndTime()))) {
                throw new IllegalStateException("발급 종료 시각 전에는 마감할 수 없다.");
            }
            int count = batches.eventCount(date);
            TransactionLogging.afterCommit(() -> log.info("event=COUPON_ISSUE_CLOSE_DONE businessDate={} eventCount={}", date, count));
            return count;
        }));
    }

    /*
     * 설정된 크기로 회차 ID를 읽되 오픈 이벤트는 회차마다 별도 트랜잭션에서 발행한다.
     * 일부 회차를 처리한 뒤 실패하면 재시도에서 같은 이벤트가 다시 발행될 수 있다.
     * 이벤트 ID와 발생 시각을 고정해 분석 수신자가 같은 오픈을 중복 집계하지 않게 한다.
     */
    public int open(LocalDate date, int attemptNo) {
        long cursor = 0;
        int count = 0;
        List<Long> ids;
        do {
            ids = batches.eventIds(date, cursor);
            for (long id : ids) {
                count += Objects.requireNonNull(transactions.execute(status -> openOne(date, attemptNo, id)));
                cursor = id;
            }
        } while (ids.size() == properties.batch().pageSize());
        return count;
    }

    private int openOne(LocalDate date, int attemptNo, long id) {
        guard.assertCurrentAttempt(CouponBatchTask.OPEN.jobName(), date, attemptNo);
        CouponEvent event = events.findByIdForUpdate(id).orElseThrow();
        LocalDateTime now = LocalDateTime.now(clock);
        if (!date.equals(now.toLocalDate()) || now.toLocalTime().isBefore(properties.issueStartTime())
                || !now.toLocalTime().isBefore(properties.issueEndTime()) || event.getStatus() != CouponEventStatus.ACTIVE) {
            return 0;
        }
        // 재실행에서도 같은 ID와 발생 시각을 사용하며 분석 수신자가 중복을 제거한다.
        publisher.publishEvent(new CouponIssueOpenedEvent("coupon-issue-opened:" + id, date.atTime(properties.issueStartTime()),
                event.getCampaignId(), event.getStoreId(), id));
        return 1;
    }
}
